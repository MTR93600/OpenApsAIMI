package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A byte range of the file that belongs to one calendar month, named `yyyy-MM`. */
internal data class AimiMonthRange(val month: String, val start: Long, val endExclusive: Long)

/**
 * Where to cut a file, and how the part being archived splits by month.
 *
 * @param headerBytes size of the header line including its newline, or 0 when there is none.
 * @param cutOffset first byte to keep. Equal to [headerBytes] when there is nothing to archive.
 * @param timestampKeyMatched false only for a TRIM_TIME file where not one line carried the policy's
 * declared key - a sign the policy names the wrong key for this file. True for every other case
 * (including TRIM_LINES and RETIRE, which do not use a key at all), so callers that only care about
 * this one failure mode do not have to also check the operation.
 */
internal data class AimiCutPlan(
    val headerBytes: Long,
    val cutOffset: Long,
    val ranges: List<AimiMonthRange>,
    val timestampKeyMatched: Boolean = true,
)

/**
 * Reads a file once and works out what to archive.
 *
 * Nothing is loaded whole: the walk is done by [AimiLineScanner], and a line budget is tracked with
 * a ring buffer the size of the budget. On the 2.10 GB decision file this is a single sequential
 * read with constant memory.
 */
internal object AimiCutPlanner {

    private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

    fun plan(
        file: File,
        rule: AimiRetentionRule,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AimiCutPlan = when (rule.op) {
        AimiRetentionOp.TRIM_TIME  -> planByTime(file, rule, nowMs, zone)
        AimiRetentionOp.TRIM_LINES -> planByLines(file, rule, nowMs, zone)
        else                       -> AimiCutPlan(0L, 0L, emptyList())
    }

    private fun planByTime(file: File, rule: AimiRetentionRule, nowMs: Long, zone: ZoneId): AimiCutPlan {
        val key = AimiTimestampKey(rule.timestampKey ?: return AimiCutPlan(0L, 0L, emptyList()))
        val cutoffMs = nowMs - rule.hotDays * 24L * 60L * 60L * 1000L
        var headerBytes = 0L
        var first = true
        var cutOffset = -1L
        var sawTimestamp = false
        val ranges = mutableListOf<AimiMonthRange>()
        var runMonth = month(nowMs, zone)

        AimiLineScanner.forEachLine(file) { start, end, prefix ->
            if (first && rule.hasHeader) {
                headerBytes = end
                first = false
                return@forEachLine true
            }
            first = false
            val ts = key.extract(prefix)
            if (ts != null) sawTimestamp = true
            if (ts != null && ts >= cutoffMs) {
                cutOffset = start
                return@forEachLine false
            }
            val label = if (ts != null) month(ts, zone).also { runMonth = it } else runMonth
            val last = ranges.lastOrNull()
            if (last != null && last.month == label) {
                ranges[ranges.size - 1] = last.copy(endExclusive = end)
            } else {
                ranges.add(AimiMonthRange(label, start, end))
            }
            true
        }

        // Not one line carried the key: the policy names the wrong key for this file. Cutting now
        // would archive the whole live file every day, so do nothing and let the caller log it.
        if (!sawTimestamp) return AimiCutPlan(headerBytes, headerBytes, emptyList(), timestampKeyMatched = false)
        if (cutOffset < 0L) cutOffset = ranges.lastOrNull()?.endExclusive ?: headerBytes
        if (cutOffset <= headerBytes) return AimiCutPlan(headerBytes, headerBytes, emptyList())
        return AimiCutPlan(headerBytes, cutOffset, ranges)
    }

    private fun planByLines(file: File, rule: AimiRetentionRule, nowMs: Long, zone: ZoneId): AimiCutPlan {
        val budget = rule.hotLines
        val starts = LongArray(budget)
        var count = 0
        var write = 0
        var headerBytes = 0L
        var first = true

        AimiLineScanner.forEachLine(file) { start, end, _ ->
            if (first && rule.hasHeader) {
                headerBytes = end
                first = false
                return@forEachLine true
            }
            first = false
            starts[write] = start
            write = (write + 1) % budget
            if (count < budget) count++
            true
        }

        if (count < budget) return AimiCutPlan(headerBytes, headerBytes, emptyList())
        val oldestKept = starts[write]
        if (oldestKept <= headerBytes) return AimiCutPlan(headerBytes, headerBytes, emptyList())
        val range = AimiMonthRange(month(nowMs, zone), headerBytes, oldestKept)
        return AimiCutPlan(headerBytes, oldestKept, listOf(range))
    }

    private fun month(epochMs: Long, zone: ZoneId): String =
        MONTH.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
