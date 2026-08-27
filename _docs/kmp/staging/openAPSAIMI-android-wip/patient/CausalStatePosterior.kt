package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalHypothesis
import kotlin.math.abs
import kotlin.math.max

enum class CausalStateId {
    UNKNOWN,
    FAST_MEAL,
    PROLONGED_MEAL,
    DAWN_ENDOGENOUS,
    POST_HYPO_RECOVERY,
    STRESS_RESISTANCE,
    EXERCISE_AFTERBURN,
    INFLAMMATORY_DRIFT,
    ABSORPTION_UNCERTAIN,
}

data class CausalStatePosterior(
    val fastMealProb: Double = 0.0,
    val prolongedMealProb: Double = 0.0,
    val dawnEndogenousProb: Double = 0.0,
    val postHypoRecoveryProb: Double = 0.0,
    val stressResistanceProb: Double = 0.0,
    val exerciseAfterburnProb: Double = 0.0,
    val inflammatoryDriftProb: Double = 0.0,
    val absorptionUncertainProb: Double = 0.0,
    val dominant: CausalStateId = CausalStateId.UNKNOWN,
    val dominantConfidence: Double = 0.0,
    val learningQuality: Double = 0.0,
    val source: String = "causal_posterior_v1",
) {
    val mealConfidence: Double
        get() = max(fastMealProb, prolongedMealProb * 0.94).coerceIn(0.0, 1.0)

    val protectiveConfidence: Double
        get() = maxOf(
            dawnEndogenousProb,
            postHypoRecoveryProb,
            stressResistanceProb,
            (exerciseAfterburnProb * 0.92).coerceIn(0.0, 1.0),
            inflammatoryDriftProb,
            absorptionUncertainProb,
        ).coerceIn(0.0, 1.0)

    fun supportsMealInterpretation(
        minConfidence: Double = 0.55,
        mealMargin: Double = 0.08,
    ): Boolean = mealConfidence >= minConfidence && mealConfidence >= protectiveConfidence + mealMargin

    fun learningContextClean(minQuality: Double = LEARNING_QUALITY_MIN): Boolean =
        learningQuality >= minQuality &&
            !(dominant == CausalStateId.POST_HYPO_RECOVERY && dominantConfidence >= 0.68) &&
            !(dominant == CausalStateId.ABSORPTION_UNCERTAIN && dominantConfidence >= 0.55)

    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("fast_meal_prob", fastMealProb)
            put("prolonged_meal_prob", prolongedMealProb)
            put("dawn_endogenous_prob", dawnEndogenousProb)
            put("post_hypo_recovery_prob", postHypoRecoveryProb)
            put("stress_resistance_prob", stressResistanceProb)
            put("exercise_afterburn_prob", exerciseAfterburnProb)
            put("inflammatory_drift_prob", inflammatoryDriftProb)
            put("absorption_uncertain_prob", absorptionUncertainProb)
            put("meal_confidence", mealConfidence)
            put("protective_confidence", protectiveConfidence)
            put("dominant", dominant.name)
            put("dominant_confidence", dominantConfidence)
            put("learning_quality", learningQuality)
            put("source", source)
        }

    companion object {
        /**
         * Minimum causal learning quality to allow PKPD kinetics ([AdaptivePkPdEstimator]) learning.
         * Lowered 0.58 → 0.50 (field data 2026-07-24): a UAM-only user (COB=0) has chronic absorption
         * uncertainty, so `learningQuality` sat < 0.58 on ~93% of ticks and DIA/peak learning ran on only
         * ~7% (fasting windows). 0.50 restores learning on the cleaner ~17% while still excluding the
         * noisiest ticks. Single source of truth: also used by the replay quality "unclean" flag.
         */
        const val LEARNING_QUALITY_MIN = 0.50

        val EMPTY = CausalStatePosterior(learningQuality = 1.0)
    }
}

internal object CausalStatePosteriorBuilder {

    fun build(
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        latentState: PhysioLatentState?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        userIntent: UserIntentSummary,
        thermalBelief: ThermalBeliefDigest?,
        falseMealSuppression: Boolean,
        eventMemory: PatientEventMemory = PatientEventMemory.EMPTY,
    ): CausalStatePosterior {
        val thermal = thermalBelief ?: ThermalBeliefDigest.EMPTY
        val fastMealProb = buildFastMealProb(
            mealAbsorptionOutput = mealAbsorptionOutput,
            latentState = latentState,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            userIntent = userIntent,
            falseMealSuppression = falseMealSuppression,
        )
        val prolongedMealProb = buildProlongedMealProb(
            mealAbsorptionOutput = mealAbsorptionOutput,
            latentState = latentState,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            userIntent = userIntent,
            falseMealSuppression = falseMealSuppression,
        )
        val dawnEndogenousProb = buildDawnEndogenousProb(
            latentState = latentState,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            thermalBelief = thermal,
            falseMealSuppression = falseMealSuppression,
        )
        val postHypoRecoveryProb = buildPostHypoRecoveryProb(
            latentState = latentState,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            userIntent = userIntent,
            eventMemory = eventMemory,
        )
        val stressResistanceProb = buildStressResistanceProb(
            latentState = latentState,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            thermalBelief = thermal,
            userIntent = userIntent,
        )
        val exerciseAfterburnProb = buildExerciseAfterburnProb(
            latentState = latentState,
            patternSnapshot = patternSnapshot,
            thermalBelief = thermal,
            userIntent = userIntent,
        )
        val inflammatoryDriftProb = buildInflammatoryDriftProb(
            latentState = latentState,
            patternSnapshot = patternSnapshot,
            thermalBelief = thermal,
            userIntent = userIntent,
        )
        val absorptionUncertainProb = buildAbsorptionUncertainProb(
            latentState = latentState,
            patternSnapshot = patternSnapshot,
            falseMealSuppression = falseMealSuppression,
            mealConfidence = max(fastMealProb, prolongedMealProb * 0.94),
            protectiveConfidence = maxOf(
                dawnEndogenousProb,
                postHypoRecoveryProb,
                stressResistanceProb,
                exerciseAfterburnProb,
                inflammatoryDriftProb,
            ),
        )

        val hypotheses = linkedMapOf(
            CausalStateId.FAST_MEAL to fastMealProb,
            CausalStateId.PROLONGED_MEAL to prolongedMealProb,
            CausalStateId.DAWN_ENDOGENOUS to dawnEndogenousProb,
            CausalStateId.POST_HYPO_RECOVERY to postHypoRecoveryProb,
            CausalStateId.STRESS_RESISTANCE to stressResistanceProb,
            CausalStateId.EXERCISE_AFTERBURN to exerciseAfterburnProb,
            CausalStateId.INFLAMMATORY_DRIFT to inflammatoryDriftProb,
            CausalStateId.ABSORPTION_UNCERTAIN to absorptionUncertainProb,
        )
        val dominantEntry = hypotheses.maxByOrNull { it.value }
        val dominantConfidence = dominantEntry?.value?.coerceIn(0.0, 1.0) ?: 0.0
        val dominant = if (dominantConfidence >= 0.20) {
            dominantEntry?.key ?: CausalStateId.UNKNOWN
        } else {
            CausalStateId.UNKNOWN
        }
        val mealConfidence = max(fastMealProb, prolongedMealProb * 0.94).coerceIn(0.0, 1.0)
        val protectiveConfidence = maxOf(
            dawnEndogenousProb,
            postHypoRecoveryProb,
            stressResistanceProb,
            (exerciseAfterburnProb * 0.92).coerceIn(0.0, 1.0),
            inflammatoryDriftProb,
            absorptionUncertainProb,
        ).coerceIn(0.0, 1.0)
        val ambiguity = (1.0 - abs(mealConfidence - protectiveConfidence)).coerceIn(0.0, 1.0)
        val learningQuality = buildLearningQuality(
            latentState = latentState,
            falseMealSuppression = falseMealSuppression,
            dominant = dominant,
            dominantConfidence = dominantConfidence,
            mealConfidence = mealConfidence,
            protectiveConfidence = protectiveConfidence,
            absorptionUncertainProb = absorptionUncertainProb,
            ambiguity = ambiguity,
            eventMemory = eventMemory,
        )

        return CausalStatePosterior(
            fastMealProb = fastMealProb,
            prolongedMealProb = prolongedMealProb,
            dawnEndogenousProb = dawnEndogenousProb,
            postHypoRecoveryProb = postHypoRecoveryProb,
            stressResistanceProb = stressResistanceProb,
            exerciseAfterburnProb = exerciseAfterburnProb,
            inflammatoryDriftProb = inflammatoryDriftProb,
            absorptionUncertainProb = absorptionUncertainProb,
            dominant = dominant,
            dominantConfidence = dominantConfidence,
            learningQuality = learningQuality,
            source = "causal_posterior_v1",
        )
    }

    private fun buildFastMealProb(
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        latentState: PhysioLatentState?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        userIntent: UserIntentSummary,
        falseMealSuppression: Boolean,
    ): Double {
        val phaseSignal = when (mealAbsorptionOutput?.phase) {
            MealAbsorptionPhase.FIRST_WAVE -> max(0.62, mealAbsorptionOutput.belief)
            MealAbsorptionPhase.PEAK_CORRECTION -> max(0.50, mealAbsorptionOutput.belief * 0.84)
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.MEAL_DECLARED,
            PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
            PhysiologicalPatternId.MEAL_FIRST_WAVE,
        )
        val intentSignal = if (userIntent.hasMealRisk) userIntent.avgConfidence * 0.32 else 0.0
        val base = combineSignals(
            phaseSignal,
            (latentState?.mealProb ?: 0.0) * 0.78,
            (hypothesisState?.mealProb ?: 0.0) * 0.88,
            patternSignal,
            intentSignal,
        )
        return if (falseMealSuppression) base * 0.62 else base
    }

    private fun buildProlongedMealProb(
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        latentState: PhysioLatentState?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        userIntent: UserIntentSummary,
        falseMealSuppression: Boolean,
    ): Double {
        val phaseSignal = when (mealAbsorptionOutput?.phase) {
            MealAbsorptionPhase.SECOND_WAVE,
            MealAbsorptionPhase.INTER_WAVE,
            -> max(0.50, mealAbsorptionOutput.belief * 0.88)

            MealAbsorptionPhase.LATE_FAT -> max(0.58, mealAbsorptionOutput.belief)
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.MEAL_SECOND_WAVE,
            PhysiologicalPatternId.LATE_FAT_PROTEIN,
        )
        // Declared slow-carb meal: mild prolonged prime only (never fast-meal → no front-loading).
        val intentSignal = if (userIntent.hasSlowCarbMeal) userIntent.avgConfidence * 0.30 else 0.0
        val base = combineSignals(
            phaseSignal,
            (latentState?.mealProb ?: 0.0) * 0.56,
            (hypothesisState?.lateFatProb ?: 0.0) * 0.92,
            patternSignal,
            intentSignal,
        )
        return if (falseMealSuppression) base * 0.72 else base
    }

    private fun buildDawnEndogenousProb(
        latentState: PhysioLatentState?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        thermalBelief: ThermalBeliefDigest,
        falseMealSuppression: Boolean,
    ): Double = combineSignals(
        (latentState?.endogenousGlucoseDrive ?: 0.0) * 0.90,
        (hypothesisState?.dawnEndogenousProb ?: 0.0) * 0.94,
        patternSnapshot.maxConfidence(
            PhysiologicalPatternId.DAWN_CORTISOL,
            PhysiologicalPatternId.MALE_CIRCADIAN_HORMONAL,
            PhysiologicalPatternId.FEMALE_CYCLE_HORMONAL,
            PhysiologicalPatternId.ENDOGENOUS_COUNTER_REGULATORY,
            PhysiologicalPatternId.NGR_NIGHT_GROWTH,
            PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
        ),
        if (falseMealSuppression) 0.18 else 0.0,
        if (thermalBelief.hypothesis == ThermalHypothesis.CYCLE_BBT_RISE) 0.18 else 0.0,
    )

    private fun buildPostHypoRecoveryProb(
        latentState: PhysioLatentState?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        userIntent: UserIntentSummary,
        eventMemory: PatientEventMemory,
    ): Double = combineSignals(
        latentState?.postHypoReboundProb ?: 0.0,
        (hypothesisState?.postHypoProb ?: 0.0) * 0.92,
        patternSnapshot.maxConfidence(PhysiologicalPatternId.POST_HYPO_REBOUND),
        if (userIntent.hasAlcohol) max(0.36, userIntent.avgConfidence * 0.58) else 0.0,
        // Declared hypo recovery: strong prime so the protective post-hypo path engages on declaration.
        if (userIntent.hasHypoRecovery) max(0.60, userIntent.avgConfidence * 0.75) else 0.0,
        eventMemory.correctionFragilityScore * 0.42,
        eventMemory.postHyperExhaustionScore * 0.26,
    )

    private fun buildStressResistanceProb(
        latentState: PhysioLatentState?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        thermalBelief: ThermalBeliefDigest,
        userIntent: UserIntentSummary,
    ): Double = combineSignals(
        (latentState?.transientResistanceProb ?: 0.0) * 0.86,
        (hypothesisState?.stressProb ?: 0.0) * 0.94,
        patternSnapshot.maxConfidence(
            PhysiologicalPatternId.STRESS_CORTISOL_ACUTE,
            PhysiologicalPatternId.PSYCHOSOCIAL_STRESS,
            PhysiologicalPatternId.HRV_DEPRESSED,
            PhysiologicalPatternId.RECOVERY_NEEDED,
            PhysiologicalPatternId.INFECTION_RISK,
        ),
        if (userIntent.hasStress) userIntent.avgConfidence * 0.34 else 0.0,
        if (userIntent.hasIllness) userIntent.avgConfidence * 0.22 else 0.0,
        thermalBelief.inflammationIndex * 0.42,
    )

    private fun buildExerciseAfterburnProb(
        latentState: PhysioLatentState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        thermalBelief: ThermalBeliefDigest,
        userIntent: UserIntentSummary,
    ): Double = combineSignals(
        if (userIntent.hasActivity) max(0.42, userIntent.avgConfidence * 0.78) else 0.0,
        patternSnapshot.maxConfidence(
            PhysiologicalPatternId.EXERCISE_ACUTE,
            PhysiologicalPatternId.POST_EXERCISE_SENSITIVITY,
        ),
        thermalBelief.recoveryBurden * 0.34,
        ((latentState?.circadianSiFactor ?: 1.0) - 0.82).coerceIn(0.0, 0.18),
    )

    private fun buildInflammatoryDriftProb(
        latentState: PhysioLatentState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        thermalBelief: ThermalBeliefDigest,
        userIntent: UserIntentSummary,
    ): Double = combineSignals(
        thermalBelief.inflammationIndex,
        thermalBelief.recoveryBurden * 0.46,
        patternSnapshot.maxConfidence(
            PhysiologicalPatternId.INFECTION_RISK,
            PhysiologicalPatternId.RECOVERY_NEEDED,
            PhysiologicalPatternId.CONTEXT_ILLNESS,
        ),
        (latentState?.inflammationRecovery ?: 0.0) * 0.66,
        (latentState?.transientResistanceProb ?: 0.0) * 0.30,
        if (userIntent.hasIllness) max(0.35, userIntent.avgConfidence * 0.52) else 0.0,
    )

    private fun buildAbsorptionUncertainProb(
        latentState: PhysioLatentState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        falseMealSuppression: Boolean,
        mealConfidence: Double,
        protectiveConfidence: Double,
    ): Double {
        val ambiguity = (1.0 - abs(mealConfidence - protectiveConfidence)).coerceIn(0.0, 1.0)
        return combineSignals(
            (1.0 - (latentState?.sensorConfidence ?: 0.0)).coerceIn(0.0, 1.0),
            ambiguity * 0.62,
            if (falseMealSuppression && mealConfidence in 0.30..0.72) 0.34 else 0.0,
            patternSnapshot.maxConfidence(PhysiologicalPatternId.COMPRESSION_ARTIFACT),
        )
    }

    private fun buildLearningQuality(
        latentState: PhysioLatentState?,
        falseMealSuppression: Boolean,
        dominant: CausalStateId,
        dominantConfidence: Double,
        mealConfidence: Double,
        protectiveConfidence: Double,
        absorptionUncertainProb: Double,
        ambiguity: Double,
        eventMemory: PatientEventMemory,
    ): Double {
        var quality = latentState?.sensorConfidence ?: 0.0
        quality *= 1.0 - absorptionUncertainProb * 0.55
        quality *= 1.0 - ambiguity * 0.25
        if (falseMealSuppression) quality *= 0.88
        if (protectiveConfidence >= mealConfidence + 0.10) quality *= 0.84
        quality *= 1.0 - eventMemory.correctionFragilityScore * 0.22
        quality *= 1.0 - eventMemory.postHyperExhaustionScore * 0.12
        if (dominant in setOf(CausalStateId.POST_HYPO_RECOVERY, CausalStateId.ABSORPTION_UNCERTAIN)) {
            quality *= 1.0 - dominantConfidence * 0.30
        }
        return quality.coerceIn(0.0, 1.0)
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
