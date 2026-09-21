package app.aaps.plugins.aps.openAPSAIMI.retention

import android.content.Context
import android.os.Environment
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

/**
 * Picks the directories a retention pass should sweep, out of the ones telemetry writers can use.
 *
 * The telemetry writers do not all agree on one directory: [AuditorJsonlExport][app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorJsonlExport]
 * writes wherever [AimiStorageHelper] resolves, [AimiHormonitorStudyExporterMTR][app.aaps.plugins.aps.openAPSAIMI.physio.AimiHormonitorStudyExporterMTR]
 * writes to a hardcoded `Documents/AAPS` and falls back to an app-scoped directory when shared
 * storage is denied, and [AimiStorageHelper.getAimiDirectory] itself can resolve to either of those
 * or to an internal-only fallback. A pass over only one of them leaves telemetry and `.overflow`
 * files in the others archived by nobody and deleted by nobody.
 *
 * [candidates] may contain the same directory more than once (for example, `getAimiDirectory()`
 * already resolved to `Documents/AAPS`) and may contain directories that do not exist. This keeps
 * only the ones that exist and are directories, de-duplicated by canonical path so the same
 * directory is never swept twice in one pass.
 */
internal fun aimiRetentionCandidateDirectories(candidates: List<File>): List<File> {
    val seenCanonicalPaths = LinkedHashSet<String>()
    val result = mutableListOf<File>()
    for (candidate in candidates) {
        if (!candidate.exists() || !candidate.isDirectory) continue
        val canonicalPath = try {
            candidate.canonicalPath
        } catch (e: IOException) {
            candidate.absolutePath
        }
        if (seenCanonicalPaths.add(canonicalPath)) result.add(candidate)
    }
    return result
}

/**
 * Runs [runPass] once for each of [directories], in order, and returns the sum of what it returned.
 *
 * A directory whose pass throws does not stop the rest of the sweep: the throw is caught, [onFailure]
 * is called with it, and the loop moves on to the next directory - that directory contributes nothing
 * to the total. [CancellationException] is never caught this way: it is rethrown immediately, before
 * [onFailure] runs, so a caller inside a coroutine that was told to stop is not kept alive by treating
 * cancellation as just another failed directory. This mirrors how [AimiRetentionManager.runOnce]
 * already treats [CancellationException] at the per-rule level.
 *
 * Before each directory, [shouldStop] is checked. When it returns true, the sweep stops without
 * running that directory (or any later one), [onStopped] is called once, and the directories already
 * swept keep their contribution to the total. This lets a caller with a bounded run window (WorkManager
 * gives a worker about ten minutes, and the first pass over a 2 GB file can take a while) hand off a
 * graceful stop instead of being killed mid-archive.
 *
 * Pure and Android-free on purpose, so it can be unit-tested without instantiating a `Worker`.
 */
internal fun sweepDirectories(
    directories: List<File>,
    shouldStop: () -> Boolean = { false },
    onStopped: () -> Unit = {},
    onFailure: (File, Exception) -> Unit = { _, _ -> },
    runPass: (File) -> Int,
): Int {
    var total = 0
    for (directory in directories) {
        if (shouldStop()) {
            onStopped()
            break
        }
        try {
            total += runPass(directory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(directory, e)
        }
    }
    return total
}

/**
 * Runs one retention pass a day, over every directory telemetry writers might use.
 *
 * The whole body runs inside one try/catch, so nothing - not even a resolver throwing while building
 * the candidate list - can escape [doWorkAndLog] as anything other than a logged failure: the design
 * is that this worker always returns success. A daily job that retries on error would turn one
 * unreadable file into a retry storm, and the next scheduled pass tries again anyway.
 * [CancellationException] is the one exception this does not turn into success: it is rethrown so the
 * worker's own cancellation is not swallowed.
 */
@HiltWorker
class AimiRetentionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    aapsLogger: AAPSLogger,
    fabricPrivacy: FabricPrivacy,
    private val storageHelper: AimiStorageHelper,
) : LoggingWorker(appContext, workerParams, Dispatchers.IO, aapsLogger, fabricPrivacy) {

    override suspend fun doWorkAndLog(): Result {
        try {
            val candidates = listOfNotNull(
                storageHelper.getAimiDirectory(),
                File(Environment.getExternalStorageDirectory(), "Documents/AAPS"),
                applicationContext.getExternalFilesDir(null)?.let { File(it, "AAPS") },
            )
            val directories = aimiRetentionCandidateDirectories(candidates)

            val totalChanged = sweepDirectories(
                directories = directories,
                shouldStop = { isStopped },
                onStopped = {
                    aapsLogger.info(
                        LTag.APS,
                        "AimiRetentionWorker: stopped early, one or more directories were not swept this pass",
                    )
                },
                onFailure = { directory, error ->
                    aapsLogger.error(LTag.APS, "AimiRetentionWorker: pass failed for ${directory.absolutePath}", error)
                },
            ) { directory ->
                val manager = AimiRetentionManager(
                    aimiDir = directory,
                    logger = { message -> aapsLogger.info(LTag.APS, message) },
                    now = System::currentTimeMillis,
                    // Failures and skips (not enough space, a changed-during-trim abort, a stale
                    // retire, a mismatched timestamp key, ...) go to WARN, not INFO: this device's
                    // APS log is about 91% AUTOSENS and holds only a few hours, so a daily janitor
                    // failure logged at INFO is never actually seen (I2).
                    warn = { message -> aapsLogger.warn(LTag.APS, message) },
                )
                val changed = manager.runOnce()
                aapsLogger.info(
                    LTag.APS,
                    "AimiRetentionWorker: pass finished for ${directory.absolutePath}, $changed file(s) changed",
                )
                changed
            }

            aapsLogger.info(
                LTag.APS,
                "AimiRetentionWorker: swept ${directories.size} directory(ies), $totalChanged file(s) changed in total",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AimiRetentionWorker: pass failed", e)
        }
        return Result.success()
    }
}
