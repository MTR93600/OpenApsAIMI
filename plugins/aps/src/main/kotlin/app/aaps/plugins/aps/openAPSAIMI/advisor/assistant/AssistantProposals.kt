package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import app.aaps.plugins.aps.R
import kotlin.math.roundToInt

/**
 * Rules from the symptom map. Each rule uses numbers found in the user's own log.
 * The list is sorted by priority: the first item is the one to try first.
 */
object AssistantProposals {

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    fun build(
        lows: List<LowEpisode>,
        late: List<LateEpisode>,
        current: (AssistantSetting) -> Double,
    ): List<AssistantProposal> {
        val out = ArrayList<AssistantProposal>()

        fun down(s: AssistantSetting, by: Double, reason: Int, args: List<Any>, round: (Double) -> Double = ::round2) {
            val cur = current(s)
            val next = round((cur - by).coerceAtLeast(s.min))
            if (next < cur) out.add(AssistantProposal(s, cur, next, reason, args))
        }
        fun up(s: AssistantSetting, by: Double, reason: Int, args: List<Any>, round: (Double) -> Double = ::round2) {
            val cur = current(s)
            val next = round((cur + by).coerceAtMost(s.max))
            if (next > cur) out.add(AssistantProposal(s, cur, next, reason, args))
        }

        val serial = lows.filter { it.smbCount2h >= 8 && it.smbMinutesBetween in 0.1..4.0 }
        if (lows.isNotEmpty()) {
            val minLow = lows.minOf { it.minMmol }
            // 1. Many SMB in a row: space them out.
            if (serial.isNotEmpty()) {
                up(
                    AssistantCatalog.highBgInterval, 2.0, R.string.aimi_as_reason_series,
                    listOf(serial.size, serial.map { it.smbCount2h }.average().roundToInt(), round1(serial.map { it.smbMinutesBetween }.average()), serial.minOf { it.minMmol }),
                ) { it.coerceAtMost(6.0) }
            }
            // 2. IOB room above Max IOB, then the IOB limit itself.
            down(AssistantCatalog.priorityExtra, 1.0, R.string.aimi_as_reason_priority, listOf(lows.size, minLow), ::round1)
            down(AssistantCatalog.tailDamping, 0.05, R.string.aimi_as_reason_tail, listOf(lows.size))
            // 3. Give back less of the cut dose.
            if (lows.size >= 2) down(AssistantCatalog.redCarpet, 0.15, R.string.aimi_as_reason_red_carpet, listOf(lows.size))
            down(AssistantCatalog.priorityFactor, 0.1, R.string.aimi_as_reason_priority_factor, listOf(lows.size, minLow))
            down(AssistantCatalog.highBgMaxSmb, 0.1, R.string.aimi_as_reason_hb_max_down, listOf(lows.size), ::round1)
            down(AssistantCatalog.adaptiveBasal, 0.1, R.string.aimi_as_reason_basal, listOf(lows.size, minLow), ::round1)
            down(AssistantCatalog.maxIob, 1.0, R.string.aimi_as_reason_max_iob, listOf(lows.size, minLow), ::round1)
        } else if (late.size >= 2) {
            val avg = late.map { it.delayMinutes }.average().roundToInt()
            val args = listOf(late.size, avg)
            // Long delay: first the gate between doses, then the size of the first dose.
            if (avg >= 25) down(AssistantCatalog.highBgInterval, 1.0, R.string.aimi_as_reason_late_interval, args) { it.coerceAtLeast(2.0) }
            up(AssistantCatalog.highBgMaxSmb, 0.1, R.string.aimi_as_reason_late_hb, args, ::round1)
            up(AssistantCatalog.tailDamping, 0.05, R.string.aimi_as_reason_late_tail, args) { it.coerceAtMost(0.95) }
            up(AssistantCatalog.redCarpet, 0.05, R.string.aimi_as_reason_late_red, args)
            up(AssistantCatalog.maxSmb, 0.1, R.string.aimi_as_reason_late, args, ::round1)
            up(AssistantCatalog.priorityExtra, 0.5, R.string.aimi_as_reason_late_iob, args, ::round1)
        }
        return out
    }
}
