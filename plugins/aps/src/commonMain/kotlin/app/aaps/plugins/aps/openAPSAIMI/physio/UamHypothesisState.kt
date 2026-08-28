package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import kotlin.math.max

enum class UamHypothesisId {
    NONE,
    MEAL,
    DAWN_ENDOGENOUS,
    STRESS,
    POST_HYPO,
    LATE_FAT,
}

/**
 * Compact UAM hypothesis scores for the tick.
 *
 * `UamHypothesisStateBuilder` stays dump until `AimiBehaviorRuntimeProfile` is T1-clean.
 */
data class UamHypothesisState(
    val mealProb: Double = 0.0,
    val dawnEndogenousProb: Double = 0.0,
    val stressProb: Double = 0.0,
    val postHypoProb: Double = 0.0,
    val lateFatProb: Double = 0.0,
    val dominant: UamHypothesisId = UamHypothesisId.NONE,
    val dominantConfidence: Double = 0.0,
    val suppressMealInterpretation: Boolean = false,
    val source: String = "uam_hyp_v1",
) {
    fun mealCompatibleProb(): Double = max(mealProb, lateFatProb * 0.88)

    fun competingNonMealProb(): Double = max(
        dawnEndogenousProb,
        max(stressProb, postHypoProb),
    )

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("meal_prob", mealProb)
        put("dawn_endogenous_prob", dawnEndogenousProb)
        put("stress_prob", stressProb)
        put("post_hypo_prob", postHypoProb)
        put("late_fat_prob", lateFatProb)
        put("dominant", dominant.name)
        put("dominant_confidence", dominantConfidence)
        put("suppress_meal_interpretation", suppressMealInterpretation)
        put("source", source)
    }
}
