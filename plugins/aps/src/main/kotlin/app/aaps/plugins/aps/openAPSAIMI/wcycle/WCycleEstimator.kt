package app.aaps.plugins.aps.openAPSAIMI.wcycle

import java.time.LocalDate
import java.time.temporal.ChronoUnit

class WCycleEstimator(private val prefs: WCyclePreferences) {
    fun estimate(now: LocalDate = LocalDate.now()): Pair<Int, CyclePhase> {
        val mode = prefs.trackingMode()
        return when (mode) {
            CycleTrackingMode.MENOPAUSE      -> 0 to CyclePhase.UNKNOWN
            CycleTrackingMode.NO_MENSES_LARC -> 0 to CyclePhase.LUTEAL
            CycleTrackingMode.PERIMENOPAUSE  -> estFixed28OrVariable(now).let { (d, ph) ->
                val phase = if (d % 3 == 0 && (ph == CyclePhase.LUTEAL || ph == CyclePhase.FOLLICULAR)) CyclePhase.UNKNOWN else ph
                d to phase
            }
            else -> estFixed28OrVariable(now)
        }
    }
    private fun estFixed28OrVariable(now: LocalDate): Pair<Int, CyclePhase> {
        val start = prefs.startDom() ?: return 0 to CyclePhase.UNKNOWN
        val startThisMonth = start.coerceAtMost(now.lengthOfMonth())
        val candidate = now.withDayOfMonth(startThisMonth)
        val cycleStart = if (!candidate.isAfter(now)) candidate else {
            val prev = now.minusMonths(1)
            prev.withDayOfMonth(start.coerceAtMost(prev.lengthOfMonth()))
        }
        val days = ChronoUnit.DAYS.between(cycleStart, now).toInt()
        val len = if (prefs.trackingMode() == CycleTrackingMode.CALENDAR_VARIABLE) prefs.avgLen() else 28
        val day = ((days % len) + len) % len
        val phase = when (day) {
            in 0..4 -> CyclePhase.MENSTRUATION
            in 5 until (len*0.46).toInt() -> CyclePhase.FOLLICULAR
            in (len*0.46).toInt()..(len*0.54).toInt() -> CyclePhase.OVULATION
            in (len*0.55).toInt() until len -> CyclePhase.LUTEAL
            else -> CyclePhase.UNKNOWN
        }
        return day to phase
    }
}
