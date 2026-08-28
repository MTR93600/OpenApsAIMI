package app.aaps.plugins.aps.openAPSAIMI.wcycle

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlin.time.Clock

class WCycleEstimator(private val prefs: WCyclePreferences) {
    fun estimate(now: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())): Pair<Int, CyclePhase> {
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
        val startThisMonth = start.coerceAtMost(lengthOfMonth(now))
        val candidate = LocalDate(now.year, now.month, startThisMonth)
        val cycleStart = if (candidate <= now) candidate else {
            val prev = now.minus(DatePeriod(months = 1))
            LocalDate(prev.year, prev.month, start.coerceAtMost(lengthOfMonth(prev)))
        }
        val days = cycleStart.daysUntil(now)
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

    private fun lengthOfMonth(date: LocalDate): Int {
        val first = LocalDate(date.year, date.month, 1)
        return first.daysUntil(first.plus(DatePeriod(months = 1)))
    }
}
