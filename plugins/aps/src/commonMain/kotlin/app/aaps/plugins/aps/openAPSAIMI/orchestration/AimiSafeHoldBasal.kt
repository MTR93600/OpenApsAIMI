package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import kotlin.math.abs

/**
 * The temp basal of a safe hold: a loop tick that threw, so AIMI decided nothing.
 *
 * Until 2026-11-20 a safe hold commanded nothing, and the running temp basal went on until it ended.
 * In the field report that was 0.05 U/h during a 50-minute rise; it could as well be a high temp
 * during a fall. The owner's rule since then:
 *
 * - **zero temp** when glucose is at the hypo threshold, or falling fast enough to reach it within
 *   30 minutes;
 * - otherwise **the profile basal**, which is what the pump would run with no loop at all;
 * - **no change** when the inputs cannot be trusted (not a number, no profile basal), when glucose
 *   is old (then a temp above the profile basal is still brought down to it, as oref does with old
 *   glucose), or when the pump already does the right thing.
 *
 * Never an SMB, and never more than the profile basal. Pure, so it is tested on every platform.
 */
object AimiSafeHoldBasal {

    /** How long a hold temp basal lasts. The next normal tick replaces it anyway. */
    const val DURATION_MINUTES = 30

    /** Glucose older than this is not used to decide a zero temp. */
    const val MAX_GLUCOSE_AGE_MINUTES = 12

    /** A zero temp with at least this much left is kept rather than sent again. */
    const val KEEP_ZERO_TEMP_MINUTES = 10

    /** Projection horizon of the fall, in 5-minute steps (6 steps = 30 minutes). */
    private const val FALL_STEPS = 6

    /** Rates closer than this are the same rate. */
    private const val SAME_RATE_U_PER_H = 0.01

    sealed interface Command {

        /** Why this was chosen, for the loop result. Plain words, no exception text. */
        val why: String

        data class Temp(val rateUPerH: Double, val durationMinutes: Int, override val why: String) : Command
        data class Keep(override val why: String) : Command
    }

    /**
     * @param bg glucose, mg/dL
     * @param delta last 5-minute change, mg/dL
     * @param shortAvgDelta short average 5-minute change, mg/dL
     * @param glucoseAgeMinutes age of [bg], or null when unknown
     * @param profileBasal the profile basal now, U/h
     * @param minBg the low end of the target, mg/dL
     * @param lgsThreshold the user's low glucose suspend threshold, mg/dL, or null
     * @param currentRate the running temp basal rate, U/h
     * @param currentRemainingMinutes minutes left on the running temp basal; 0 or less when none runs
     */
    fun decide(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        glucoseAgeMinutes: Double?,
        profileBasal: Double,
        minBg: Double,
        lgsThreshold: Int?,
        currentRate: Double,
        currentRemainingMinutes: Int,
    ): Command {
        val tempRunning = currentRemainingMinutes > 0 && currentRate.isFinite()
        if (!profileBasal.isFinite() || profileBasal < 0.0) return Command.Keep("no usable profile basal")

        val glucoseOld = glucoseAgeMinutes == null || !glucoseAgeMinutes.isFinite() || glucoseAgeMinutes > MAX_GLUCOSE_AGE_MINUTES
        if (glucoseOld || !bg.isFinite() || bg <= 0.0) {
            // Without fresh glucose a zero temp cannot be judged. A temp above the profile basal is
            // still brought down to it: that can only remove insulin.
            return if (tempRunning && currentRate > profileBasal + SAME_RATE_U_PER_H) {
                Command.Temp(profileBasal, DURATION_MINUTES, "glucose too old: temp brought down to the profile basal")
            } else {
                Command.Keep("glucose too old: no change")
            }
        }

        val threshold = HypoThresholdMath.computeHypoThreshold(minBg, lgsThreshold)
        if (!threshold.isFinite()) return Command.Keep("no usable hypo threshold")

        val fall = listOf(delta, shortAvgDelta).filter { it.isFinite() }.minOrNull()
        if (fall == null) {
            // No usable trend: only glucose already at the threshold can be judged.
            return if (bg <= threshold) zeroTemp(tempRunning, currentRate, currentRemainingMinutes)
            else Command.Keep("no usable glucose trend")
        }
        val projected = if (fall < 0.0) bg + FALL_STEPS * fall else bg
        val zeroJustified = bg <= threshold || projected <= threshold
        if (zeroJustified) return zeroTemp(tempRunning, currentRate, currentRemainingMinutes)

        if (!tempRunning) return Command.Keep("profile basal already running")
        if (abs(currentRate - profileBasal) <= SAME_RATE_U_PER_H) return Command.Keep("profile basal already running")
        return Command.Temp(profileBasal, DURATION_MINUTES, "profile basal")
    }

    private fun zeroTemp(tempRunning: Boolean, currentRate: Double, currentRemainingMinutes: Int): Command {
        if (tempRunning && currentRate <= SAME_RATE_U_PER_H && currentRemainingMinutes >= KEEP_ZERO_TEMP_MINUTES) {
            return Command.Keep("zero temp already running")
        }
        return Command.Temp(0.0, DURATION_MINUTES, "zero temp: glucose low or falling to the hypo threshold")
    }
}
