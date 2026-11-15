package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinKineticsView
import kotlin.concurrent.Volatile

/**
 * Limits how fast the DIA and peak that dose may move from one tick to the next.
 *
 * The causal context of the previous tick ([CausalKineticsContextMemory]) can flip from one state to
 * another in one tick, for example from stress (DIA +1 h) to a meal (DIA -0.8 h). Without a limit the
 * dosing DIA would jump by 1.8 h from one tick to the next. So the dosing DIA may move at most
 * [MAX_DIA_STEP_H] and the peak at most [MAX_PEAK_STEP_MIN] per tick, compared with the values that
 * dosed the tick before.
 *
 * A tick is one call of [limit], that is one loop run (one `OpenAPSAIMIPlugin.invoke`), not a fixed
 * time. With a 5-minute CGM that is about 0.25 h and 2 min per 5 minutes, so DIA 5 h to 9 h takes
 * 16 ticks, about 80 min. With a 1-minute CGM (Libre 3, ONE+), or when extra runs happen, the loop
 * can run about every minute, and the same 16 steps can take about 16 min. The plan says "per tick"
 * (owner decision); whether it should be per 5 minutes instead is an open point for the owner.
 *
 * The limit holds for every change of the dosing DIA and peak, not only for the causal context: a
 * change of the profile insulin, the learned values or the physio, site and trajectory shifts also
 * reach the new value step by step.
 *
 * No limit on the first tick, and none after more than [maxGapMs] without a tick (loop stopped, phone
 * off): the value then starts from the target. The view keeps the target in
 * [InsulinKineticsView.targetEffective], so the log shows both.
 */
class DosingKineticsRateLimiter(private val maxGapMs: Long = CausalKineticsContextMemory.MAX_AGE_MS) {

    private class Last(val tickTimeMs: Long, val diaHours: Double, val peakMinutes: Double)

    @Volatile
    private var last: Last? = null

    /**
     * Returns [resolved] with its effective DIA and peak limited against the last tick, and keeps the
     * limited values for the next tick. A target that is not a finite number is not limited and
     * clears the memory, so the next tick starts from its own target.
     */
    fun limit(nowMs: Long, resolved: InsulinKineticsView): InsulinKineticsView {
        val target = resolved.effective
        if (!target.diaHours.isFinite() || !target.peakMinutes.isFinite()) {
            last = null
            return resolved.copy(targetEffective = target)
        }
        val previous = last
        val gapMs = previous?.let { nowMs - it.tickTimeMs }
        val limited = if (previous == null || gapMs == null || gapMs < 0L || gapMs > maxGapMs) {
            target
        } else {
            target.copy(
                diaHours = step(previous.diaHours, target.diaHours, MAX_DIA_STEP_H),
                peakMinutes = step(previous.peakMinutes, target.peakMinutes, MAX_PEAK_STEP_MIN),
            )
        }
        last = Last(nowMs, limited.diaHours, limited.peakMinutes)
        return resolved.copy(effective = limited, targetEffective = target)
    }

    private fun step(previous: Double, target: Double, maxStep: Double): Double =
        target.coerceIn(previous - maxStep, previous + maxStep)

    companion object {

        /** Largest change of the dosing DIA per tick, in hours. */
        const val MAX_DIA_STEP_H = 0.25

        /** Largest change of the dosing peak per tick, in minutes. */
        const val MAX_PEAK_STEP_MIN = 2.0
    }
}
