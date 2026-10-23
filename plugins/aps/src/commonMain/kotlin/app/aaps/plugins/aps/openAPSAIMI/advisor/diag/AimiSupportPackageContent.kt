package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * What goes into the AIMI Advisor support package, and in which order. Shared, so both phones send
 * the same package.
 *
 * Entries, the same names and content as `origin/dev_OAPSAIMI` (`generateAndShareReport` in
 * `AimiProfileAdvisorActivity`):
 * 1. [REPORT_ENTRY]: the diagnostic report, when it is not empty.
 * 2. [DECISIONS_ENTRY]: the lines of `AIMI_Decisions.jsonl` from the last 24 hours, when the
 *    journal can be read. `scripts/aimi_replay_fixture.py` reads this entry.
 * 3. [ML_CSV]: header and the newest rows of the SMB training corpus ([AimiSupportCsvTail]).
 *
 * Nothing else is added. The package carries health data, so a new entry is a decision, not a
 * side effect. Only file names and row counts are logged, never file content.
 */
internal object AimiSupportPackageContent {

    const val REPORT_ENTRY = "Diagnostic_Report.txt"
    const val DECISIONS_ENTRY = "AIMI_Decisions_Last24h.jsonl"
    const val ML_CSV = "oapsaimiML2_records.csv"

    /** How far back the decision log goes. */
    const val DECISION_WINDOW_MS = 24 * 60 * 60 * 1000L

    /** Kept decision lines are written in pieces of about this many characters. */
    private const val WRITE_CHUNK_CHARS = 64 * 1024

    /**
     * Writes every entry into [out]. [out] is neither finished nor aborted here; the caller owns it.
     *
     * @param report the diagnostic report text. Empty means no report entry.
     * @param nowMs the current time. Decision lines older than 24 hours before it are left out.
     * @return how many entries were written. `0` means there is nothing to send.
     */
    fun write(out: AimiSupportZipOutput, report: String, storage: AimiStorage, logger: AAPSLogger, nowMs: Long): Int {
        var entries = 0
        if (report.isNotEmpty()) {
            out.beginEntry(REPORT_ENTRY)
            out.write(report.encodeToByteArray())
            out.endEntry()
            entries++
        }
        if (addDecisionLogLast24h(out, storage, nowMs - DECISION_WINDOW_MS)) entries++
        if (addCsvTail(out, storage, logger, ML_CSV)) entries++
        return entries
    }

    /**
     * Streams the decision journal, not `readLines`: it gains a line every loop tick and is never
     * truncated, so holding it whole would risk running the heap out on the very device whose
     * problem the package is meant to capture. Lines end with `\n`, as on Android.
     *
     * A read failure while streaming is not an error, as before: the lines read so far are kept.
     * A write failure throws, and the whole package fails.
     *
     * Both storages catch what the line callback throws and only answer `false`, which would hide a
     * failed ZIP write and let a broken package be shared as complete. So the failure is kept here,
     * the walk stops, and the failure is thrown again once [AimiStorage.forEachLine] returns.
     *
     * @return `true` when the entry was written.
     */
    private fun addDecisionLogLast24h(out: AimiSupportZipOutput, storage: AimiStorage, cutoffTimeMs: Long): Boolean {
        val path = T3cRuntimeHistoryReader.aimiDecisionsJsonlPath(storage)
        if (!storage.exists(path) || !storage.canRead(path)) return false
        out.beginEntry(DECISIONS_ENTRY)
        val chunk = StringBuilder()
        var writeFailure: Throwable? = null
        storage.forEachLine(path) { line ->
            if (writeFailure != null) return@forEachLine
            try {
                if (AimiSupportDecisionLogFilter.keep(line, cutoffTimeMs)) {
                    chunk.append(line).append('\n')
                    if (chunk.length >= WRITE_CHUNK_CHARS) {
                        out.write(chunk.toString().encodeToByteArray())
                        chunk.clear()
                    }
                }
            } catch (e: Throwable) {
                writeFailure = e
                // Thrown on so the storage stops reading. It is thrown again below in any case.
                throw e
            }
        }
        writeFailure?.let { failure ->
            // An Error (out of memory, mostly) becomes an exception, so the export answers Failed
            // instead of ending the app or being swallowed.
            throw failure as? Exception ?: IllegalStateException("support package write failed", failure)
        }
        if (chunk.isNotEmpty()) out.write(chunk.toString().encodeToByteArray())
        out.endEntry()
        return true
    }

    /**
     * Adds the tail of an AIMI CSV, header first.
     *
     * A missing or unreadable file is skipped in silence. The package is a best effort report, and
     * failing to build it would leave the user with nothing to send. The text is ready before the
     * entry starts, so a read failure never leaves a half written entry behind.
     *
     * The file is streamed, and only the header and the newest rows are kept
     * ([AimiSupportCsvTail.Collector]), so the corpus is never held whole. A read that stops early
     * skips the entry, as an unreadable file did before.
     *
     * @return `true` when the entry was written.
     */
    private fun addCsvTail(out: AimiSupportZipOutput, storage: AimiStorage, logger: AAPSLogger, fileName: String): Boolean {
        val text: String
        val rows: Int
        val totalRows: Int
        try {
            val source = storage.file(fileName)
            if (!storage.exists(source) || !storage.canRead(source)) {
                logger.info(LTag.APS, "AIMI_DIAG: $fileName not found, not added to the package")
                return false
            }
            val collector = AimiSupportCsvTail.Collector()
            if (!storage.forEachLine(source, collector::add)) {
                logger.warn(LTag.APS, "AIMI_DIAG: could not read $fileName, not added to the package")
                return false
            }
            val tail = collector.result() ?: return false
            text = AimiSupportCsvTail.toText(tail)
            rows = tail.body.size
            totalRows = collector.totalRows
        } catch (e: Exception) {
            logger.warn(LTag.APS, "AIMI_DIAG: could not add $fileName: ${e.message}")
            return false
        }
        out.beginEntry(fileName)
        out.write(text.encodeToByteArray())
        out.endEntry()
        logger.info(LTag.APS, "AIMI_DIAG: added $fileName to the package ($rows rows of $totalRows)")
        return true
    }
}
