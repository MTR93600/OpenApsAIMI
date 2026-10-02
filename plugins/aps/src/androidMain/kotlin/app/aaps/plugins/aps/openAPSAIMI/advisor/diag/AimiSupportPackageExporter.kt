package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiSharing
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds and shares the AIMI Advisor support ZIP.
 *
 * Observation / tools only. File I/O on a background dispatcher. Not on the dose path.
 *
 * Package contents (from parked Advisor Activity + `origin/dev_OAPSAIMI` @ `02c90656b1`):
 * 1. `Diagnostic_Report.txt` including `[ACTIVE PROFILE]` from [ProfileFunction]
 * 2. `AIMI_Decisions_Last24h.jsonl` (last 24 hours, when the log can be read)
 * 3. tail of `oapsaimiML2_records.csv` (header + last 3000 rows)
 */
class AimiSupportPackageExporter(
    private val context: Context,
    private val preferences: Preferences,
    private val logger: AAPSLogger,
    private val profileFunction: ProfileFunction,
    private val rh: ResourceHelper,
    private val storage: AimiStorage,
    private val sharing: AimiSharing,
) {

    suspend fun build(issue: String): AimiSupportPackageResult {
        return try {
            val diagManager = AimiDiagnosticsManager(context, preferences, logger)
            val runningProfile = runCatching { profileFunction.getProfile() }.getOrNull()
            val runningProfileName = runCatching { profileFunction.getProfileName() }.getOrNull()
            val reportContent = diagManager.generateReport(
                userMessage = issue,
                activeProfile = runningProfile,
                activeProfileName = runningProfileName,
            )
            val zipFileName = "AIMI_Support_Package_${aimiWallClockMs()}.zip"
            val zipFile = File(context.cacheDir, zipFileName)

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
                if (reportContent.isNotEmpty()) {
                    out.putNextEntry(ZipEntry("Diagnostic_Report.txt"))
                    out.write(reportContent.toByteArray())
                    out.closeEntry()
                }
                addDecisionLogLast24h(out)
                addCsvTail(out, "oapsaimiML2_records.csv")
            }

            if (zipFile.exists() && zipFile.length() > 0) {
                AimiSupportPackageResult.Ready(AimiPath(zipFile.absolutePath))
            } else {
                AimiSupportPackageResult.Empty
            }
        } catch (e: Exception) {
            logger.error(LTag.APS, "AIMI_DIAG: Failed to generate/share report", e)
            AimiSupportPackageResult.Failed(e.message)
        }
    }

    /**
     * Hands the finished package to the platform's share sheet.
     *
     * The subject, the covering message and the chooser title are the same strings as before; only
     * the hand-off itself moved, into [AimiSharing].
     */
    fun share(zip: AimiPath, issue: String) {
        sharing.shareFile(
            path = zip,
            mimeType = MIME_ZIP,
            subject = rh.gs(R.string.aimi_diag_subject, Date().toString()),
            text = "AIMI Support Package attached (ZIP).\n\nDetails: $issue",
            chooserTitle = rh.gs(R.string.aimi_diag_chooser),
        )
    }

    /**
     * Adds the tail of an AIMI CSV to the support package, header first.
     *
     * A missing or unreadable file is skipped in silence. The package is a best effort report, and
     * failing to build it would leave the user with nothing to send.
     */
    private fun addCsvTail(out: ZipOutputStream, fileName: String) {
        try {
            val source = storage.file(fileName)
            if (!storage.exists(source) || !storage.canRead(source)) {
                logger.info(LTag.APS, "AIMI_DIAG: $fileName not found, not added to the package")
                return
            }
            val lines = storage.readLines(source)
            val tail = AimiSupportCsvTail.select(lines) ?: return
            out.putNextEntry(ZipEntry(fileName))
            out.write(AimiSupportCsvTail.toText(tail).toByteArray(Charsets.UTF_8))
            out.closeEntry()
            logger.info(
                LTag.APS,
                "AIMI_DIAG: added $fileName to the package (${tail.body.size} rows of ${lines.size - 1})"
            )
        } catch (e: Exception) {
            logger.warn(LTag.APS, "AIMI_DIAG: could not add $fileName: ${e.message}")
        }
    }

    private fun addDecisionLogLast24h(out: ZipOutputStream) {
        val path = T3cRuntimeHistoryReader.aimiDecisionsJsonlPath(storage)
        if (!storage.exists(path) || !storage.canRead(path)) return
        out.putNextEntry(ZipEntry("AIMI_Decisions_Last24h.jsonl"))
        val cutoffTime = aimiWallClockMs() - (24 * 60 * 60 * 1000L)
        val writer = BufferedWriter(OutputStreamWriter(out))
        // Streamed, not readLines: this journal gains a line every loop tick and is never truncated,
        // so holding it whole would risk the export running the heap out on the very device whose
        // problem it is meant to capture.
        storage.forEachLine(path) { line ->
            if (AimiSupportDecisionLogFilter.keep(line, cutoffTime)) {
                writer.write(line)
                writer.newLine()
            }
        }
        writer.flush()
        out.closeEntry()
    }

    private companion object {

        const val MIME_ZIP = "application/zip"
    }
}
