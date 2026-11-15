package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinKineticsView
import kotlin.concurrent.Volatile

/**
 * Limits how fast the DIA and peak that dose may move over time.
 *
 * The causal context of the previous tick ([CausalKineticsContextMemory]) can flip from one state to
 * another in one tick, for example from stress (DIA +1 h) to a meal (DIA -0.8 h). Without a limit the
 * dosing DIA would jump by 1.8 h from one tick to the next. So the dosing DIA may move at most
 * [MAX_DIA_STEP_H] and the peak at most [MAX_PEAK_STEP_MIN] per [STEP_PERIOD_MS] (5 minutes), compared
 * with the values that dosed the last call.
 *
 * The step counts time, not loop runs (step P1c, owner decision 2026-11-15): the allowed step is
 * `base step x time since the last call / 5 minutes`, capped at one full step per call. On a 5-minute
 * loop with regular readings that is about one full step per tick, as before (exactly one for a 5:00
 * gap; a loop run that comes at 4:50 gets 0.97 of a step), so DIA 5 h to 9 h takes about 80 min.
 * With a 1-minute CGM (Libre 3, ONE+), or when extra loop runs happen, each call moves only its share
 * of the step, so the same change also takes about 80 min. Two calls close together share one step;
 * they do not get two. After missed readings (gap of 10 or 15 minutes) one call still moves at most
 * one step (0.25 h, 2 min), as in P1b: owner choice, prudence first. A gap of 0 or less
 * (same time, or the clock went back) allows no move; after a negative gap the time starts again
 * from that call.
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
     * Returns [resolved] with its effective DIA and peak limited against the last call, and keeps the
     * limited values for the next call. A target that is not a finite number is not limited and
     * clears the memory, so the next tick starts from its own target.
     */
    fun limit(nowMs: Long, resolved: InsulinKineticsView): InsulinKineticsView {
        val target = resolved.effective
        if (!target.diaHours.isFinite() || !target.peakMinutes.isFinite()) {
            last = null
            return resolved.copy(targetEffective = target)
        }
        val previous = last
        val limited = if (previous == null || nowMs - previous.tickTimeMs > maxGapMs) {
            target
        } else {
            val share = stepShare(nowMs - previous.tickTimeMs)
            target.copy(
                diaHours = step(previous.diaHours, target.diaHours, MAX_DIA_STEP_H * share),
                peakMinutes = step(previous.peakMinutes, target.peakMinutes, MAX_PEAK_STEP_MIN * share),
            )
        }
        last = Last(nowMs, limited.diaHours, limited.peakMinutes)
        return resolved.copy(effective = limited, targetEffective = target)
    }

    /** Part of one full step that [gapMs] allows: 1.0 for 5 minutes, 0.0 for a gap of 0 or less. */
    private fun stepShare(gapMs: Long): Double =
        // Capped at one full step per call: after missed readings (a 10 or 15 min gap) one call
        // never moves more than P1b allowed. Owner choice, prudence first.
        if (gapMs <= 0L) 0.0 else (gapMs.toDouble() / STEP_PERIOD_MS).coerceAtMost(1.0)

    private fun step(previous: Double, target: Double, maxStep: Double): Double =
        target.coerceIn(previous - maxStep, previous + maxStep)

    companion object {

        /** Largest change of the dosing DIA per [STEP_PERIOD_MS], in hours. */
        const val MAX_DIA_STEP_H = 0.25

        /** Largest change of the dosing peak per [STEP_PERIOD_MS], in minutes. */
        const val MAX_PEAK_STEP_MIN = 2.0

        /** Time that allows one full step: 5 minutes. */
        const val STEP_PERIOD_MS = 5 * 60_000L
    }
}
