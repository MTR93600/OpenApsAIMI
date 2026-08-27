package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import kotlin.math.max

enum class UamHypothesisId {
    NONE,
    MEAL,
    DAWN_ENDOGENOUS,
    STRESS,
    POST_HYPO,
    LATE_FAT,
}

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

internal object UamHypothesisStateBuilder {

    fun build(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        uamConfidence: Double,
        behaviorProfile: AimiBehaviorRuntimeProfile? = null,
    ): UamHypothesisState {
        val patternSuppressMeal = patternSnapshot?.suppressMealInterpretation == true
        val mealProb = buildMealProb(
            phaseOutput = phaseOutput,
            mealAbsorptionOutput = mealAbsorptionOutput,
            patternSnapshot = patternSnapshot,
            uamConfidence = uamConfidence,
        )
        val dawnEndogenousProb = buildDawnEndogenousProb(
            phaseOutput = phaseOutput,
            patternSnapshot = patternSnapshot,
        )
        val stressProb = buildStressProb(
            phaseOutput = phaseOutput,
            patternSnapshot = patternSnapshot,
        )
        val postHypoProb = buildPostHypoProb(
            patternSnapshot = patternSnapshot,
            correctionAggressionDecision = correctionAggressionDecision,
        )
        val lateFatProb = buildLateFatProb(
            mealAbsorptionOutput = mealAbsorptionOutput,
            patternSnapshot = patternSnapshot,
        )

        val competingNonMeal = max(
            dawnEndogenousProb,
            max(stressProb, postHypoProb),
        )
        val dominanceMargin = behaviorProfile?.competingNonMealDominanceMargin() ?: 0.10
        val dominanceFloor = behaviorProfile?.competingNonMealConfidenceFloor() ?: 0.60
        val suppressionCap = behaviorProfile?.mealSuppressionCap() ?: 0.32
        val dampedMealProb = when {
            patternSuppressMeal -> mealProb.coerceAtMost(suppressionCap)
            competingNonMeal >= mealProb + dominanceMargin && competingNonMeal >= dominanceFloor ->
                (mealProb * 0.58).coerceAtMost(suppressionCap)

            else -> mealProb
        }

        val hypotheses = linkedMapOf(
            UamHypothesisId.MEAL to dampedMealProb,
            UamHypothesisId.DAWN_ENDOGENOUS to dawnEndogenousProb,
            UamHypothesisId.STRESS to stressProb,
            UamHypothesisId.POST_HYPO to postHypoProb,
            UamHypothesisId.LATE_FAT to lateFatProb,
        )
        val dominantEntry = hypotheses.maxByOrNull { it.value }
        val dominantConfidence = dominantEntry?.value?.coerceIn(0.0, 1.0) ?: 0.0
        val dominant = if (dominantConfidence >= 0.20) {
            dominantEntry?.key ?: UamHypothesisId.NONE
        } else {
            UamHypothesisId.NONE
        }
        val suppressMealInterpretation = patternSuppressMeal ||
            (
                dominant in setOf(
                    UamHypothesisId.DAWN_ENDOGENOUS,
                    UamHypothesisId.STRESS,
                    UamHypothesisId.POST_HYPO,
                ) &&
                    dominantConfidence >= dampedMealProb + (behaviorProfile?.suppressMealDecisionMargin() ?: 0.08) &&
                    dominantConfidence >= (behaviorProfile?.suppressMealDecisionFloor() ?: 0.60)
                )

        return UamHypothesisState(
            mealProb = dampedMealProb.coerceIn(0.0, 1.0),
            dawnEndogenousProb = dawnEndogenousProb.coerceIn(0.0, 1.0),
            stressProb = stressProb.coerceIn(0.0, 1.0),
            postHypoProb = postHypoProb.coerceIn(0.0, 1.0),
            lateFatProb = lateFatProb.coerceIn(0.0, 1.0),
            dominant = dominant,
            dominantConfidence = dominantConfidence,
            suppressMealInterpretation = suppressMealInterpretation,
        )
    }

    private fun buildMealProb(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        uamConfidence: Double,
    ): Double {
        val phaseMeal = if (phaseOutput?.phase?.isMealRisk == true) phaseOutput.confidence else 0.0
        val absorptionMeal = when {
            mealAbsorptionOutput == null -> 0.0
            mealAbsorptionOutput.phase == MealAbsorptionPhase.LATE_FAT -> 0.0
            mealAbsorptionOutput.phase.isActive -> mealAbsorptionOutput.belief
            else -> 0.0
        }
        val patternMeal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.MEAL_DECLARED,
            PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
            PhysiologicalPatternId.MEAL_FIRST_WAVE,
            PhysiologicalPatternId.MEAL_SECOND_WAVE,
        )
        val uamSupport = when {
            absorptionMeal >= 0.45 || phaseMeal >= 0.45 || patternMeal >= 0.45 ->
                (uamConfidence.coerceIn(0.0, 1.0) * 0.22)

            else -> 0.0
        }
        return combineSignals(
            phaseMeal,
            absorptionMeal,
            patternMeal,
            uamSupport,
        )
    }

    private fun buildDawnEndogenousProb(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val phaseSignal = when {
            phaseOutput == null -> 0.0
            phaseOutput.phase.isEndogenousRisk -> phaseOutput.confidence
            phaseOutput.phase.isHormonalRisk -> phaseOutput.confidence * 0.82
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.DAWN_CORTISOL,
            PhysiologicalPatternId.MALE_CIRCADIAN_HORMONAL,
            PhysiologicalPatternId.FEMALE_CYCLE_HORMONAL,
            PhysiologicalPatternId.ENDOGENOUS_COUNTER_REGULATORY,
            PhysiologicalPatternId.NGR_NIGHT_GROWTH,
            PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
        )
        return combineSignals(phaseSignal, patternSignal)
    }

    private fun buildStressProb(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val phaseSignal = when (phaseOutput?.phase) {
            PhysiologicalPhase.STRESS_CORTISOL -> phaseOutput.confidence
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.STRESS_CORTISOL_ACUTE,
            PhysiologicalPatternId.PSYCHOSOCIAL_STRESS,
            PhysiologicalPatternId.HRV_DEPRESSED,
            PhysiologicalPatternId.RECOVERY_NEEDED,
            PhysiologicalPatternId.INFECTION_RISK,
        )
        return combineSignals(phaseSignal, patternSignal)
    }

    private fun buildPostHypoProb(
        patternSnapshot: PhysiologicalPatternSnapshot?,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
    ): Double {
        val reboundGuard = when (correctionAggressionDecision?.tier) {
            CorrectionAggressionGate.Tier.REBOUND_GUARD -> 0.74
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(PhysiologicalPatternId.POST_HYPO_REBOUND)
        return combineSignals(reboundGuard, patternSignal)
    }

    private fun buildLateFatProb(
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val phaseSignal = when (mealAbsorptionOutput?.phase) {
            MealAbsorptionPhase.LATE_FAT -> max(0.58, mealAbsorptionOutput.belief)
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(PhysiologicalPatternId.LATE_FAT_PROTEIN)
        return combineSignals(phaseSignal, patternSignal)
    }

    private fun combineSignals(vararg values: Double): Double {
        if (values.isEmpty()) return 0.0
        var remainingNeutral = 1.0
        for (value in values) {
            remainingNeutral *= 1.0 - value.coerceIn(0.0, 1.0)
        }
        return (1.0 - remainingNeutral).coerceIn(0.0, 1.0)
    }

    private fun PhysiologicalPatternSnapshot?.maxConfidence(vararg ids: PhysiologicalPatternId): Double =
        this?.active
            ?.filter { reading -> reading.id in ids }
            ?.maxOfOrNull { reading -> reading.confidence.coerceIn(0.0, 1.0) }
            ?: 0.0
}
