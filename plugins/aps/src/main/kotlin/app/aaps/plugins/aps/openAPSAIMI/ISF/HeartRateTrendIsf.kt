package app.aaps.plugins.aps.openAPSAIMI.ISF

/**
 * Makes insulin stronger when the heart rate is climbing against its own hour while the person is
 * still and glucose is over the floor.
 *
 * ## What this is
 *
 * The reading is "an unexplained heart rate at rest suggests resistance, so the same glucose error
 * needs a little more insulin". Commanded sensitivity is in mg/dL per unit, so multiplying it by
 * [ISF_MULTIPLIER] **lowers** the number and **raises** the dose. This is the only heart-rate path in
 * the engine that raises a dose; every other one lowers it.
 *
 * ## Why it now has a rise rule
 *
 * Until 2026-09-17 it had no gate at all — no carbs test, no rise test, no meal phase, only
 * `steps < 100 && trend > 1.1 && bg > 110`. On the 2026-09-17 01:00 episode glucose went 83 to 195
 * in half an hour while the ten-minute heart rate climbed from 62 to 100 against a calmer hour, with
 * no steps: every condition held, so the gesture made insulin 11 % stronger in the middle of the
 * rise it was reacting to.
 *
 * During a rise that fast the heart rate elevation is a **consequence** of the rise, not information
 * about its cause — the adrenergic response follows the glucose, and on that episode the reading went
 * 88, then 62, then 100 within twenty minutes while glucose only climbed. Adding insulin for it
 * counts the same event twice. So above [RISE_SUSPEND_MGDL_PER_5MIN] the gesture stands down.
 *
 * The threshold is not invented here: it is the value `PhysiologicalPhaseClassifier` already measured
 * as "too steep to be cortisol alone" — genuine cortisol never rose faster than 9.1 mg/dL per 5 min
 * over 952 ticks and 14 episodes. The same reasoning applies: a rise this fast is not hormonal, so a
 * heart rate says nothing about it.
 *
 * ## Why the baseline must be real
 *
 * When the one-hour window holds no heart-rate record the engine substitutes 80 bpm, and on any
 * exception it substitutes 80 for all four averages. The trend is then a measured ten-minute average
 * divided by a number nobody measured, and for this person a real ten-minute average over 88 bpm
 * would trip the 1.1 ratio on that substitute alone. A dose may not be strengthened on a made-up
 * baseline, so the caller must say whether the baseline was measured.
 */
object HeartRateTrendIsf {

    /** Ratio of the ten-minute heart rate to the one-hour heart rate above which the gesture fires. */
    const val TREND_RATIO: Double = 1.1

    /** Steps in the last ten minutes at or above which this is movement, not resistance. */
    const val MAX_STEPS_10M: Int = 100

    /** Glucose at or below this is not worth strengthening a dose for, in mg/dL. */
    const val MIN_GLUCOSE_MGDL: Double = 110.0

    /**
     * Glucose rise, in mg/dL per 5 minutes, at or above which the gesture stands down.
     *
     * Same value and same reasoning as
     * [app.aaps.plugins.aps.openAPSAIMI.ISF.StressIsfFloor.RISE_HOLD_MGDL_PER_5MIN]: above it the
     * rise is too fast to be hormonal, so the heart rate carries no information about its cause.
     */
    const val RISE_SUSPEND_MGDL_PER_5MIN: Double = 11.0

    /** What the commanded sensitivity is multiplied by when the gesture fires. */
    const val ISF_MULTIPLIER: Double = 0.9

    /**
     * The multiplier to apply to the commanded sensitivity: [ISF_MULTIPLIER] when the gesture fires,
     * 1.0 otherwise. Never above 1.0, so this can only strengthen a dose or do nothing.
     *
     * Pure: it reads no clock and keeps no state.
     *
     * @param steps10m steps in the last ten minutes.
     * @param avgBpm10 the ten-minute mean heart rate.
     * @param avgBpm60 the one-hour mean heart rate, used as the baseline.
     * @param baselineIsReal false when [avgBpm60] is a substitute rather than a measurement.
     * @param bgMgdl glucose now.
     * @param deltaMgdl5m glucose change over the last 5 minutes.
     */
    fun multiplier(
        steps10m: Int,
        avgBpm10: Double,
        avgBpm60: Double,
        baselineIsReal: Boolean,
        bgMgdl: Double,
        deltaMgdl5m: Double,
    ): Double {
        if (!baselineIsReal) return 1.0
        if (!avgBpm10.isFinite() || !avgBpm60.isFinite() || !bgMgdl.isFinite() || !deltaMgdl5m.isFinite()) {
            return 1.0
        }
        if (avgBpm60 <= 0.0 || avgBpm10 <= 0.0) return 1.0
        if (steps10m >= MAX_STEPS_10M) return 1.0
        if (bgMgdl <= MIN_GLUCOSE_MGDL) return 1.0
        if (deltaMgdl5m >= RISE_SUSPEND_MGDL_PER_5MIN) return 1.0
        if (avgBpm10 / avgBpm60 <= TREND_RATIO) return 1.0
        return ISF_MULTIPLIER
    }
}
