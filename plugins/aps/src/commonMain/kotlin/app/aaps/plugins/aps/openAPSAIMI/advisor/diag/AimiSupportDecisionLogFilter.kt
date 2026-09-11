package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

/**
 * Last-24h filter for `AIMI_Decisions.jsonl` lines in the support ZIP.
 *
 * Same fast heuristic as parked `AimiProfileAdvisorActivity.generateAndShareReport`:
 * look for `"timestamp":123456789` without parsing the whole JSON line.
 * Lines with no timestamp are dropped (cleaner log).
 */
internal object AimiSupportDecisionLogFilter {

    fun keep(line: String, cutoffTimeMs: Long): Boolean {
        val tsIdx = line.indexOf("\"timestamp\":")
        if (tsIdx == -1) return false
        val start = tsIdx + 12
        var end = start
        while (end < line.length && line[end].isDigit()) {
            end++
        }
        if (end <= start) return false
        val ts = line.substring(start, end).toLongOrNull() ?: return false
        return ts >= cutoffTimeMs
    }
}
