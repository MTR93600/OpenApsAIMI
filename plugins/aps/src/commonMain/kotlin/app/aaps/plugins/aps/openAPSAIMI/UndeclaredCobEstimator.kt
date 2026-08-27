package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import kotlin.math.max
import kotlin.math.min

/**
 * Estimates a bounded **virtual COB** (grams) for an *undeclared* meal, so the basal (TBR)
 * anticipation path can react before the user declares carbs.
 *
 * Design constraints (Option A — TBR only):
 * - Pure function, no shared mutable state, no Android / async dependencies → fully unit-testable.
 * - The result is meant to be **added to `effectiveCOB`** feeding the prediction curves only.
 *   It must never be turned into an autonomous SMB by the caller.
 * - Strong, non-defeatable safety gates for the CFRD context: exercise, hypo / post-hypo,
 *   pulmonary exacerbation / elevated-HR inflammation, and any false-meal suppression
 *   (dawn / stress / post-hypo) fully mute the estimate.
 *
 * The core signal is the glucose appearance rate `Ra` (mg/dL/min, already weight-capped by
 * `ContinuousStateEstimator`). It is converted
 * to a carb-equivalent through the carb sensitivity factor `CSF = ISF / CR` (mg/dL per gram), then
 * bounded by the weight / TDD physiological ceiling and the user preference cap.
 */
object UndeclaredCobEstimator {

    /** Minimum fused meal probability required to consider an undeclared meal at all. */
    private const val MEAL_PROB_THRESHOLD = 0.55

    /** BG must be genuinely rising to attribute a Ra signal to a meal. */
    private const val MIN_DELTA_MGDL_5M = 1.0
    private const val MIN_SLOPE_FROM_MIN_DEVIATION = 1.0

    /** BG at/below this is treated as a hypo zone → never inject virtual carbs. */
    private const val HYPO_GUARD_MGDL = 80.0

    /** Absorption horizon used to turn an instantaneous Ra into a pending carb-equivalent. */
    private const val RA_HORIZON_MIN = 45.0

    /** Conservative de-rating so the estimator systematically under-shoots. */
    private const val CONSERVATIVE_FACTOR = 0.5

    /** Plausible single-meal carbohydrate ceiling per kg of body weight (g/kg). */
    private const val G_PER_KG_CEILING = 1.2

    /** A single meal should not exceed this fraction of the daily carb-equivalent (TDD × CR). */
    private const val TDD_MEAL_FRACTION = 0.4

    data class Input(
        val estimatedRaMgdlPerMin: Double,
        val isfMgdlPerU: Double,
        val carbRatioGPerU: Double,
        val bgMgdl: Double,
        val deltaMgdl5m: Double,
        val slopeFromMinDeviation: Double,
        val patientWeightKg: Double,
        val tdd24hU: Double?,
        val stepsLast5m: Int,
        val stepsLast15m: Int,
        val activityDetected: Boolean,
        val mealProb: Double,
        val falseMealSuppression: Boolean,
        val exerciseLockoutActive: Boolean,
        val postHypoActive: Boolean,
        val cfrdExacerbationActive: Boolean,
        val hrInflammationElevated: Boolean,
        val maxGramsPref: Double,
    )

    data class Result(
        val grams: Double,
        val gated: Boolean,
        val reason: String,
        val rawGrams: Double,
        val capGrams: Double,
    ) {
        fun toLogString(): String =
            "g=${aimiFmt1(grams)} raw=${aimiFmt1(rawGrams)} cap=${aimiFmt1(capGrams)} " +
                "gated=$gated reason=$reason"

        companion object {
            fun gated(reason: String): Result = Result(0.0, true, reason, 0.0, 0.0)
        }
    }

    /**
     * @return a [Result] whose [Result.grams] is the bounded virtual COB to add to `effectiveCOB`.
     *   Returns `grams = 0.0` with [Result.gated] = true whenever a safety gate or a missing
     *   signal blocks the estimate.
     */
    fun estimate(input: Input): Result {
        // ── Safety gates (order = most protective first) ───────────────────────
        if (input.falseMealSuppression) return Result.gated("false_meal_suppression")
        if (input.postHypoActive) return Result.gated("post_hypo")
        if (input.bgMgdl <= HYPO_GUARD_MGDL) return Result.gated("hypo_zone")
        if (input.cfrdExacerbationActive) return Result.gated("cfrd_exacerbation")
        if (input.hrInflammationElevated) return Result.gated("hr_inflammation")
        if (input.exerciseLockoutActive || input.activityDetected) return Result.gated("exercise_activity")
        if (input.mealProb < MEAL_PROB_THRESHOLD) return Result.gated("meal_prob_low")

        // ── BG dynamics confirmation (real rise, not sensor noise) ─────────────
        val risingConfirmed =
            input.deltaMgdl5m >= MIN_DELTA_MGDL_5M &&
                input.slopeFromMinDeviation >= MIN_SLOPE_FROM_MIN_DEVIATION
        if (!risingConfirmed) return Result.gated("no_confirmed_rise")

        // ── Signal validity ───────────────────────────────────────────────────
        if (input.estimatedRaMgdlPerMin <= 0.0 || !input.estimatedRaMgdlPerMin.isFinite()) {
            return Result.gated("no_ra_signal")
        }
        val csfMgdlPerG = carbSensitivityFactor(input.isfMgdlPerU, input.carbRatioGPerU)
            ?: return Result.gated("invalid_csf")

        // ── Ra → carb-equivalent (conservative), scaled by confidence ──────────
        val rawGrams =
            (input.estimatedRaMgdlPerMin * RA_HORIZON_MIN * CONSERVATIVE_FACTOR) / csfMgdlPerG
        val confidenceScaled = rawGrams * input.mealProb.coerceIn(0.0, 1.0)

        // ── Physiological ceiling (weight + TDD) and preference cap ────────────
        val capGrams = ceilingGrams(input)
        val grams = confidenceScaled.coerceIn(0.0, capGrams)

        return Result(
            grams = grams,
            gated = grams <= 0.0,
            reason = if (grams <= 0.0) "below_min" else "ra_meal_estimate",
            rawGrams = rawGrams,
            capGrams = capGrams,
        )
    }

    /** CSF = ISF / CR (mg/dL per gram). Null when inputs are not usable. */
    internal fun carbSensitivityFactor(isfMgdlPerU: Double, carbRatioGPerU: Double): Double? {
        if (!isfMgdlPerU.isFinite() || !carbRatioGPerU.isFinite()) return null
        if (isfMgdlPerU <= 0.0 || carbRatioGPerU <= 0.0) return null
        return isfMgdlPerU / carbRatioGPerU
    }

    /** Hard ceiling = min(preference cap, weight ceiling, TDD ceiling). */
    internal fun ceilingGrams(input: Input): Double {
        val prefCap = max(0.0, input.maxGramsPref)
        var cap = prefCap
        if (input.patientWeightKg > 0.0) {
            cap = min(cap, input.patientWeightKg * G_PER_KG_CEILING)
        }
        val tdd = input.tdd24hU
        val cr = input.carbRatioGPerU
        if (tdd != null && tdd > 0.0 && cr > 0.0) {
            cap = min(cap, tdd * cr * TDD_MEAL_FRACTION)
        }
        return max(0.0, cap)
    }
}
