package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlin.math.abs
import kotlin.math.exp

/**
 * The situational multiplier applied to the estimated insulin sensitivity, and its bounds.
 *
 * Pure and free of dependencies so it can be tested and replayed. See
 * `docs/adr/0008-isf-decision-architecture.md`: the sensitivity `S` answers "how strongly does insulin
 * act for this patient now" and must not carry a dosing policy, because every prediction, the MPC and
 * `ControlBarrierShield` read it, and the hypoglycaemia guard depends on those predictions being honest.
 *
 * ## The rise-rate term that was removed
 *
 * The hyperglycaemia arm used to hold a second term, `exp(-0.3 * (combinedDelta - 10))`, applied
 * whenever the rise exceeded 10 mg/dL per 5 min, so the steeper the rise the harder the sensitivity was
 * crushed. Measured in production on 2026-08-14 at BG 186.6 rising +26.4 mg/dL per 5 min, it returned
 * **0.0073** and the commanded sensitivity fell to **4.54 mg/dL/U** against a static profile of 30. On
 * 2026-08-12 at BG 290 and 297 rising, the exported factor was **-0.00** and **-0.04**: the term is
 * unbounded below and goes negative. Nothing in the corpus supports the sensitivity depending on the
 * rate of rise, so the term is gone rather than re-tuned.
 *
 * ## The BG dependence that was kept, and its calibration
 *
 * Estimated from outcomes over 96 clean descents in the support-package corpus (>= 30 min, >= 25 mg/dL
 * fall, no carbs on board, appearance rate below 0.30, at least 0.8 U absorbed), as
 * `-dBG / insulin absorbed`. Endogenous glucose production is ignored, so each figure is a lower bound
 * on the true sensitivity:
 *
 * | starting BG | n  | median estimate | relative to the low band |
 * |---|---|---|---|
 * | 70-140      | 27 | 24.3 mg/dL/U | 1.00 |
 * | 140-200     | 45 | 22.6 mg/dL/U | 0.93 |
 * | 200-400     | 24 | 18.7 mg/dL/U | 0.77 |
 *
 * The compression is real and it is about **x1.3 across the whole range**. [HYPER_COMPRESSION_AT_SPAN]
 * is set so the factor reproduces that: 0.783 at BG 240 against a measured 0.77. It is bounded below by
 * [HYPER_COMPRESSION_FLOOR] so the factor cannot leave the range the measurement covers, however high
 * glucose goes.
 *
 * ## Why the falling arm is untouched
 *
 * [factorFor] raises the sensitivity on a fall, up to x1.4. That makes the loop predict a larger effect
 * from the insulin already on board, so it doses less — a protective direction. Lowering that cap would
 * permit **more** insulin during a fall. The same 96 descents give no support for the fall bonus either,
 * but changing it moves a hypoglycaemia path and it is out of scope here.
 */
object DynamicSensitivityPolicy {

    /** Glucose span, in mg/dL above [HYPER_COMPRESSION_START_MGDL], over which the compression applies. */
    internal const val HYPER_COMPRESSION_SPAN_MGDL: Double = 90.0

    /** Glucose above which the measured hyperglycaemic compression starts, mg/dL. */
    internal const val HYPER_COMPRESSION_START_MGDL: Double = 110.0

    /**
     * Compression reached at the end of the span, as a fraction.
     *
     * Was `0.5` — a halving of the sensitivity by BG 200, with no bound below. The corpus measures the
     * compression as x1.3 over the whole range, so the coefficient is the measured one.
     */
    internal const val HYPER_COMPRESSION_AT_SPAN: Double = 0.15

    /** Lower bound on the hyperglycaemia factor. Keeps it inside the range the 96 descents cover. */
    internal const val HYPER_COMPRESSION_FLOOR: Double = 0.75

    /** Upper bound on the falling-glucose factor. Unchanged from the previous behaviour. */
    internal const val FALLING_FACTOR_CAP: Double = 1.4

    /**
     * Lower bound on the commanded sensitivity, as a fraction of the static profile sensitivity.
     *
     * ADR 0008 step 1 proposes an unconditional exit bound of `[0.5, 2.0] x profile`. Only the **lower**
     * half is applied, because the two halves are not symmetric in risk:
     *
     * - Raising a crushed sensitivity is a tightening. `ControlBarrierShield.enforce` derives
     *   `lgh = -siMetabolic * bg` and permits `safeU = (-gamma*h - lfh) / lgh`, so a **higher**
     *   sensitivity makes `|lgh|` larger and the permitted dose **smaller**. It also makes every
     *   prediction attribute a larger effect to the insulin already on board, so the hypoglycaemia
     *   guard fires earlier rather than later. There is no path by which this bound adds insulin.
     * - Lowering a sensitivity that sits above profile would permit more insulin. Measured on the
     *   corpus, 42 of 2017 ticks (2.1 %) exceed `2.0 x profile`, and they cluster on descents — on the
     *   2026-08-12 hypoglycaemia the commanded value was 2.17 x profile at BG 66 through 45. An upper
     *   bound there is a relaxation of a protective path and needs its own measurement, so it is
     *   deliberately left out of this change.
     *
     * Measured cost of the lower bound alone: 95 of 2017 ticks (4.7 %) are below `0.5 x profile`, with a
     * minimum of **0.069** (BG 284-297 rising, 2026-08-12). On those ticks the commanded sensitivity
     * rises from a median of about 0.27 x profile to 0.50 x profile.
     */
    internal const val PROFILE_RELATIVE_FLOOR: Double = 0.5

    /**
     * Raises [commandedMgdlPerU] to at least [PROFILE_RELATIVE_FLOOR] of the profile sensitivity.
     *
     * Reduction is impossible by construction — the result is never below the input. Fails open: a
     * missing, non-finite or non-positive profile sensitivity returns the input unchanged, so a bad
     * profile read can never make the loop more aggressive than it already was.
     *
     * @param commandedMgdlPerU the commanded sensitivity, after every multiplier, mg/dL per U.
     * @param profileIsfMgdlPerU the static profile sensitivity, mg/dL per U, or null when unavailable.
     */
    fun floorAgainstProfile(commandedMgdlPerU: Double, profileIsfMgdlPerU: Double?): Double {
        if (!commandedMgdlPerU.isFinite()) return commandedMgdlPerU
        val profile = profileIsfMgdlPerU ?: return commandedMgdlPerU
        if (!profile.isFinite() || profile <= 0.0) return commandedMgdlPerU
        return maxOf(commandedMgdlPerU, profile * PROFILE_RELATIVE_FLOOR)
    }

    /**
     * The situational multiplier on the estimated sensitivity.
     *
     * @param delta the current 5-minute glucose delta, mg/dL. Null yields a neutral 1.0.
     * @param predicted the weighted mean of the recent deltas, mg/dL per 5 min. Null yields 1.0.
     * @param bgMgdl current glucose, mg/dL. Null yields 1.0.
     * @return a finite multiplier, at least [HYPER_COMPRESSION_FLOOR] and at most [FALLING_FACTOR_CAP].
     */
    fun factorFor(delta: Double?, predicted: Double?, bgMgdl: Double?): Double {
        if (delta == null || predicted == null || bgMgdl == null) return 1.0
        if (!delta.isFinite() || !predicted.isFinite() || !bgMgdl.isFinite()) return 1.0
        val combinedDelta = (delta + predicted) / 2.0
        return when {
            // Falling: raise the sensitivity, so the insulin already on board is predicted to do more.
            combinedDelta < 0.0                  ->
                exp(0.15 * abs(combinedDelta)).coerceAtMost(FALLING_FACTOR_CAP)
            // Above the start: the measured hyperglycaemic compression, and nothing else.
            bgMgdl > HYPER_COMPRESSION_START_MGDL ->
                (1.0 - ((bgMgdl - HYPER_COMPRESSION_START_MGDL) / HYPER_COMPRESSION_SPAN_MGDL) * HYPER_COMPRESSION_AT_SPAN)
                    .coerceIn(HYPER_COMPRESSION_FLOOR, 1.0)

            else                                 -> 1.0
        }
    }
}
