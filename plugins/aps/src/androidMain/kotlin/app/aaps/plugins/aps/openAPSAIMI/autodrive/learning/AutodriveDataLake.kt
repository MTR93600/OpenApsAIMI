package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * 🗂️ Autodrive Data Lake (Data Logger CSV)
 *
 * Silent recorder of the system state and of the decision taken (features / actions), one row per
 * Autodrive tick. The file is the training corpus of the Attention Gate.
 *
 * ## Never blocks the decision path
 *
 * This runs on the APS thread, on every tick, and it shares its file with a backfiller that holds
 * [AutodriveDatasetLock] across a full read-modify-rename of the whole corpus. Waiting for that lock
 * would put an unbounded file transaction in front of an insulin decision. So the write takes the
 * lock with a zero timeout; when the file is busy the row is carried in memory and appended with the
 * next successful write. A lost training row is cheap, a delayed dose is not.
 *
 * The carry-forward buffer is bounded: past [MAX_DEFERRED_ROWS] the oldest row is dropped and
 * [droppedRowCount] counts it, so a permanently stuck lock costs memory that does not grow.
 */
@SingleIn(AppScope::class)
class AutodriveDataLake @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper // Injection du centralisateur
) {

    private val logFile: File by lazy { storageHelper.getAimiFile(FILE_NAME) }

    private val deferredMonitor = Any()
    private val deferred = ArrayDeque<String>()

    /** Rows waiting for the dataset to be free. Exported for liveness reporting. */
    @Volatile
    var deferredRowCount: Int = 0
        private set

    /** Rows lost because the buffer was full while the dataset stayed busy. */
    @Volatile
    var droppedRowCount: Int = 0
        private set

    /** Ticks where the dataset was busy and the write had to be carried forward. */
    @Volatile
    var contendedWriteCount: Int = 0
        private set

    /**
     * Records a snapshot of the patient state and of the decision at time T.
     *
     * `Future_BG_45m`, `Hypo_Occurred` and `Hyper_Occurred` are left empty here; the backfiller fills
     * them from CGM history once the outcome window has passed.
     */
    fun recordSnapshot(
        state: AutoDriveState,
        rawCommand: AutoDriveCommand?,
        safeCommand: AutoDriveCommand?,
        engaged: Boolean,
        currentTimestamp: Long = aimiWallClockMs()
    ) {
        try {
            val line = formatRow(state, rawCommand, safeCommand, engaged, currentTimestamp)

            // Zero-timeout: the backfiller can hold this lock across the whole corpus.
            val written = AutodriveDatasetLock.tryWithDataset { appendPendingAnd(line) } ?: false
            if (!written) {
                contendedWriteCount++
                defer(line)
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "Autodrive Data Lake Error: " + e.message)
        }
    }

    private fun formatRow(
        state: AutoDriveState,
        rawCommand: AutoDriveCommand?,
        safeCommand: AutoDriveCommand?,
        engaged: Boolean,
        currentTimestamp: Long,
    ): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateStr = sdf.format(Date(currentTimestamp))

        // Did the shield strike? No command on a disengaged tick: the decision columns stay neutral
        // and `Engaged` says so explicitly, rather than letting the gate filter the dataset silently.
        val cbfIntervention = if (
            rawCommand != null && safeCommand != null && (
                rawCommand.scheduledMicroBolus > safeCommand.scheduledMicroBolus ||
                    rawCommand.temporaryBasalRate > safeCommand.temporaryBasalRate
                )
        ) 1 else 0

        val maskStr = if (state.physiologicalStressMask.isNotEmpty()) {
            state.physiologicalStressMask.joinToString("|")
        } else {
            "0"
        }

        return listOf(
            currentTimestamp.toString(),
            dateStr,
            aimiFmt1(state.bg),
            fmt3(state.bgVelocity),
            fmt3(state.iob),
            aimiFmt1(state.cob),
            fmt4(state.estimatedSI),
            fmt3(state.estimatedRa),
            aimiFmt1(state.patientWeightKg),
            maskStr,
            fmt3(rawCommand?.scheduledMicroBolus ?: 0.0),
            fmt3(rawCommand?.temporaryBasalRate ?: 0.0),
            fmt3(safeCommand?.scheduledMicroBolus ?: 0.0),
            fmt3(safeCommand?.temporaryBasalRate ?: 0.0),
            cbfIntervention.toString(),
            "", // Future_BG_45m — backfilled from CGM
            "", // Hypo_Occurred — backfilled from CGM
            "", // Hyper_Occurred — backfilled from CGM
            if (engaged) "1" else "0",
            AutodriveDatasetSchema.CURRENT_VERSION.toString(),
        ).joinToString(",") + "\n"
    }

    /**
     * Appends the carried-forward rows and then [line]. Caller holds [AutodriveDatasetLock].
     *
     * The buffer is only emptied once the bytes are out, so a failed write carries the same rows to
     * the next tick instead of losing them.
     */
    private fun appendPendingAnd(line: String): Boolean {
        val carried = synchronized(deferredMonitor) { deferred.toList() }
        return try {
            ensureHeader()
            FileWriter(logFile, true).use { writer ->
                carried.forEach { writer.append(it) }
                writer.append(line)
            }
            synchronized(deferredMonitor) {
                repeat(carried.size) { if (deferred.isNotEmpty()) deferred.pollFirst() }
                deferredRowCount = deferred.size
            }
            true
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "Autodrive Data Lake write failed: ${e.message}")
            false
        }
    }

    /** Creates the file with the canonical header on first use. Caller holds the dataset lock. */
    private fun ensureHeader() {
        if (!logFile.exists()) {
            logFile.writeText(AutodriveDatasetSchema.HEADER + "\n")
        }
    }

    private fun defer(line: String) {
        synchronized(deferredMonitor) {
            deferred.addLast(line)
            while (deferred.size > MAX_DEFERRED_ROWS) {
                deferred.pollFirst()
                droppedRowCount++
                aapsLogger.warn(
                    LTag.AIMI,
                    "Autodrive Data Lake: dataset busy for $MAX_DEFERRED_ROWS ticks, oldest row dropped " +
                        "(total dropped: $droppedRowCount)",
                )
            }
            deferredRowCount = deferred.size
        }
    }

    companion object {

        const val FILE_NAME = "autodrive_dataset.csv"

        /** About four hours of ticks. Beyond that the rows are stale enough not to be worth memory. */
        const val MAX_DEFERRED_ROWS = 48
    }

    private fun fmt3(value: Double): String =
        NumberFormat.DECIMAL_3.format(value, NumberFormatPlatform.SEPARATOR_DOT)

    private fun fmt4(value: Double): String =
        NumberFormat.withDecimals(4).format(value, NumberFormatPlatform.SEPARATOR_DOT)
}
