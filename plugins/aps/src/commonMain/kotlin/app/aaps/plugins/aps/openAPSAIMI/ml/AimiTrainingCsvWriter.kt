package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Appends one row to an AIMI training CSV, and only ever appends.
 *
 * These files are the corpus the SMB and basal models are trained from, on the phone, so every rule
 * here is a rule about data that cannot be recovered once it is wrong:
 *
 * - The header of an existing file is brought up to date before the row is written, because a frozen
 *   header above wider rows is what once made the SMB trainer learn `endogenousGlucoseDrive` where it
 *   meant `smbGiven`. See [TrainingCsvHeader].
 * - A line break is put in front of the row when the file does not already end with one, so a row can
 *   never be glued onto the one before it. See `AimiCorpusPruner.rowPrefix`.
 * - A row the primary location refused is written to the app scoped fallback instead, so it is not
 *   lost. A dropped row is a hole in the corpus.
 *
 * Nothing here decides a dose, and nothing here changes what a row contains: the caller builds the
 * header and the row, and this only puts them on disk.
 *
 * Two pieces of state are kept per instance, with the same lifetime as the tick that owns it: the
 * paths whose header has already been compared since the app started, and whether the "primary
 * storage refused" warning has been printed once. Both were fields of the tick before this class
 * existed.
 */
internal class AimiTrainingCsvWriter(
    private val storage: AimiStorage,
    private val aapsLogger: AAPSLogger,
) {

    /** Files whose header was already compared with the wanted one since the app started. */
    private val headerCheckedPaths = mutableSetOf<AimiPath>()

    private var primaryStorageDeniedLogged = false

    /**
     * Appends [valuesRow] to [primary], or to the app scoped file called [fallbackFileName] when the
     * primary location refuses the write.
     *
     * The fallback is resolved only once the primary has actually failed, never before, so a tick that
     * writes normally does not ask the platform for a second location at all.
     */
    fun appendRowWithFallback(
        primary: AimiPath,
        fallbackFileName: String,
        headerRow: String,
        valuesRow: String,
    ) {
        if (appendRow(primary, headerRow, valuesRow)) return
        val fallback = storage.fallbackFile(fallbackFileName)
        if (!primaryStorageDeniedLogged) {
            primaryStorageDeniedLogged = true
            aapsLogger.warn(
                LTag.APS,
                "CSV write denied on shared storage (${storage.displayPath(primary)}). " +
                    "Switching to app-scoped fallback at ${storage.displayPath(fallback)}. " +
                    "Primary exists=${storage.exists(primary)}, canRead=${storage.canRead(primary)}, " +
                    "canWrite=${storage.canWrite(primary)}",
            )
        }
        if (!appendRow(fallback, headerRow, valuesRow)) {
            aapsLogger.error(
                LTag.APS,
                "CSV write failed on both primary and fallback paths. primary=${storage.displayPath(primary)}, " +
                    "fallback=${storage.displayPath(fallback)}",
            )
        }
    }

    /**
     * Appends [valuesRow] to [csv], creating the file with [headerRow] when it is not there yet.
     *
     * [headerRow] carries its own trailing line break, the way the callers build it.
     *
     * @return `true` when the row reached [csv]. `false` means the row is not there, which is what
     *   sends [appendRowWithFallback] to the second location. Before the storage port, the same
     *   decision was taken on an exception from `java.io.File.appendText`; [AimiStorage] answers
     *   `false` instead of throwing, so the answer is read rather than caught.
     */
    fun appendRow(csv: AimiPath, headerRow: String, valuesRow: String): Boolean {
        if (!storage.exists(csv)) {
            storage.createParentDirectories(csv)
            storage.createFile(csv)
            if (!storage.appendText(csv, headerRow)) return false
        } else {
            ensureHeaderIsCurrent(csv, headerRow)
        }
        // The guard in front of the row is what stops it being glued to the last stored one. A clean
        // up used to rewrite the file without a final line break, so the next row written started on
        // the same line and the reader dropped both of them. See [AimiCorpusPruner].
        return storage.appendText(csv, AimiCorpusPruner.rowPrefix(storage.lastChar(csv)) + valuesRow + "\n")
    }

    /**
     * Makes sure an existing CSV carries the header the writer builds today.
     *
     * The rewrite itself, and why it is safe to replace the first line whatever its old shape, live in
     * [TrainingCsvHeader]. Here we only add the two things that belong to the running app: the file is
     * checked once per path per app start, and any failure is logged and swallowed, because a header
     * that could not be fixed must never stop a row from being written.
     *
     * ⚠️ ASYNC IMPACT: File I/O on the tick writer thread (same as the previous prefix-only upgrade).
     * The path is remembered after the first check so later ticks do not re-read the whole CSV.
     */
    private fun ensureHeaderIsCurrent(csv: AimiPath, headerRow: String) {
        if (!headerCheckedPaths.add(csv)) return
        runCatching {
            val outcome = TrainingCsvHeader.ensureCurrent(storage, csv, headerRow)
            if (outcome == TrainingCsvHeader.Outcome.REPLACED) {
                aapsLogger.info(LTag.APS, "CSV header replaced in place for ${storage.displayPath(csv)}")
            }
        }.onFailure { error ->
            aapsLogger.warn(LTag.APS, "CSV header refresh skipped for ${storage.displayPath(csv)}: ${error.message}")
        }
    }
}
