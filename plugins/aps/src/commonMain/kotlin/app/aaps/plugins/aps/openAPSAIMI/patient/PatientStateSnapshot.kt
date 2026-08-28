package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest

data class UserIntentSummary(
    val enabled: Boolean = false,
    val intentCount: Int = 0,
    val avgConfidence: Double = 0.0,
    val hasActivity: Boolean = false,
    val hasIllness: Boolean = false,
    val hasMealRisk: Boolean = false,
    val hasStress: Boolean = false,
    val hasAlcohol: Boolean = false,
    val hasSlowCarbMeal: Boolean = false,
    val hasHypoRecovery: Boolean = false,
    val dominantIntent: String = "NONE",
) {
    fun hasAnyIntent(): Boolean = enabled && intentCount > 0

    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("enabled", enabled)
            put("intent_count", intentCount)
            put("avg_confidence", avgConfidence)
            put("has_activity", hasActivity)
            put("has_illness", hasIllness)
            put("has_meal_risk", hasMealRisk)
            put("has_stress", hasStress)
            put("has_alcohol", hasAlcohol)
            put("has_slow_carb_meal", hasSlowCarbMeal)
            put("has_hypo_recovery", hasHypoRecovery)
            put("dominant_intent", dominantIntent)
        }

    companion object {
        val EMPTY = UserIntentSummary()
    }
}

data class PatientStateSnapshot(
    val timestampMs: Long,
    val phase: PhysiologicalPhase = PhysiologicalPhase.OFF,
    val phaseConfidence: Double = 0.0,
    val mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
    val mealAbsorptionBelief: Double = 0.0,
    val patternDominant: String? = null,
    val patternDominantConfidence: Double = 0.0,
    val mealProb: Double = 0.0,
    val endogenousGlucoseDrive: Double = 0.0,
    val transientResistanceProb: Double = 0.0,
    val sleepDebtScore: Double = 0.0,
    val postHypoReboundProb: Double = 0.0,
    val sensorConfidence: Double = 0.0,
    val falseMealSuppression: Boolean = false,
    val uamDominant: UamHypothesisId = UamHypothesisId.NONE,
    val uamDominantConfidence: Double = 0.0,
    val userIntent: UserIntentSummary = UserIntentSummary.EMPTY,
    val causalPosterior: CausalStatePosterior = CausalStatePosterior.EMPTY,
    val eventMemory: PatientEventMemory = PatientEventMemory.EMPTY,
    val thermalInflammationIndex: Double = 0.0,
    val thermalRecoveryBurden: Double = 0.0,
    val thermalHypothesis: String = "DATA_PENDING",
    val source: String = "patient_state_v1",
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("timestamp_ms", timestampMs)
            put("phase", phase.name)
            put("phase_confidence", phaseConfidence)
            put("meal_absorption_phase", mealAbsorptionPhase.name)
            put("meal_absorption_belief", mealAbsorptionBelief)
            put("pattern_dominant", patternDominant)
            put("pattern_dominant_confidence", patternDominantConfidence)
            put("meal_prob", mealProb)
            put("endogenous_glucose_drive", endogenousGlucoseDrive)
            put("transient_resistance_prob", transientResistanceProb)
            put("sleep_debt_score", sleepDebtScore)
            put("post_hypo_rebound_prob", postHypoReboundProb)
            put("sensor_confidence", sensorConfidence)
            put("false_meal_suppression", falseMealSuppression)
            put("uam_dominant", uamDominant.name)
            put("uam_dominant_confidence", uamDominantConfidence)
            put("user_intent", userIntent.toJsonObject())
            put("causal_posterior", causalPosterior.toJsonObject())
            put("event_memory", eventMemory.toJsonObject())
            put("thermal_inflammation_index", thermalInflammationIndex)
            put("thermal_recovery_burden", thermalRecoveryBurden)
            put("thermal_hypothesis", thermalHypothesis)
            put("source", source)
        }

    fun summary(): String =
        "phase=${phase.name} meal=${fmt(mealProb)} endo=${fmt(endogenousGlucoseDrive)} " +
            "resist=${fmt(transientResistanceProb)} sensor=${fmt(sensorConfidence)} " +
            "intent=${userIntent.dominantIntent} cause=${causalPosterior.dominant.name}"

    private fun fmt(value: Double): String = aimiFmt2(value)
}

internal object PatientStateEngine {

    fun build(
        timestampMs: Long,
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        latentState: PhysioLatentState?,
        hypothesisState: UamHypothesisState?,
        contextSnapshot: ContextSnapshot?,
        thermalBelief: ThermalBeliefDigest? = null,
        eventMemory: PatientEventMemory = PatientEventMemory.EMPTY,
    ): PatientStateSnapshot {
        val userIntent = buildUserIntentSummary(contextSnapshot)
        val thermal = thermalBelief ?: ThermalBeliefDigest.EMPTY
        val falseMealSuppression = latentState?.falseMealSuppression == true ||
            hypothesisState?.suppressMealInterpretation == true
        val causalPosterior = CausalStatePosteriorBuilder.build(
            mealAbsorptionOutput = mealAbsorptionOutput,
            latentState = latentState,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            userIntent = userIntent,
            thermalBelief = thermal,
            falseMealSuppression = falseMealSuppression,
            eventMemory = eventMemory,
        )
        return PatientStateSnapshot(
            timestampMs = timestampMs,
            phase = phaseOutput?.phase ?: PhysiologicalPhase.OFF,
            phaseConfidence = phaseOutput?.confidence ?: 0.0,
            mealAbsorptionPhase = mealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE,
            mealAbsorptionBelief = mealAbsorptionOutput?.belief ?: 0.0,
            patternDominant = patternSnapshot?.dominant?.name,
            patternDominantConfidence = patternSnapshot?.dominantConfidence ?: 0.0,
            mealProb = latentState?.mealProb ?: 0.0,
            endogenousGlucoseDrive = latentState?.endogenousGlucoseDrive ?: 0.0,
            transientResistanceProb = latentState?.transientResistanceProb ?: 0.0,
            sleepDebtScore = latentState?.sleepDebtScore ?: 0.0,
            postHypoReboundProb = latentState?.postHypoReboundProb ?: 0.0,
            sensorConfidence = latentState?.sensorConfidence ?: 0.0,
            falseMealSuppression = falseMealSuppression,
            uamDominant = hypothesisState?.dominant ?: UamHypothesisId.NONE,
            uamDominantConfidence = hypothesisState?.dominantConfidence ?: 0.0,
            userIntent = userIntent,
            causalPosterior = causalPosterior,
            eventMemory = eventMemory,
            thermalInflammationIndex = thermal.inflammationIndex,
            thermalRecoveryBurden = thermal.recoveryBurden,
            thermalHypothesis = thermal.hypothesis.name,
            source = "patient_state_v4",
        )
    }

    private fun buildUserIntentSummary(contextSnapshot: ContextSnapshot?): UserIntentSummary {
        if (contextSnapshot == null) {
            return UserIntentSummary.EMPTY
        }
        val dominantIntent = contextSnapshot.activeIntents
            .maxByOrNull { intentScore(it) }
            ?.let(::intentLabel)
            ?: "NONE"
        return UserIntentSummary(
            enabled = true,
            intentCount = contextSnapshot.intentCount,
            avgConfidence = contextSnapshot.avgConfidence.toDouble().coerceIn(0.0, 1.0),
            hasActivity = contextSnapshot.hasActivity,
            hasIllness = contextSnapshot.hasIllness,
            hasMealRisk = contextSnapshot.hasMealRisk,
            hasStress = contextSnapshot.hasStress,
            hasAlcohol = contextSnapshot.hasAlcohol,
            hasSlowCarbMeal = contextSnapshot.hasSlowCarbMeal,
            hasHypoRecovery = contextSnapshot.hasHypoRecovery,
            dominantIntent = dominantIntent,
        )
    }

    private fun intentScore(intent: ContextIntent): Int = intent.intensity.ordinal * 10 + when (intent) {
        is ContextIntent.Alcohol -> 7
        is ContextIntent.Activity -> 6
        is ContextIntent.Illness -> 5
        is ContextIntent.Stress -> 4
        is ContextIntent.HypoRecovery -> 8
        is ContextIntent.UnannouncedMealRisk -> 3
        is ContextIntent.SlowCarbMeal -> 3
        is ContextIntent.MenstrualCycle -> 2
        is ContextIntent.Travel -> 1
        is ContextIntent.Custom -> 0
    }

    private fun intentLabel(intent: ContextIntent): String = when (intent) {
        is ContextIntent.Activity -> "ACTIVITY"
        is ContextIntent.Illness -> "ILLNESS"
        is ContextIntent.UnannouncedMealRisk -> "MEAL_RISK"
        is ContextIntent.SlowCarbMeal -> "SLOW_CARB_MEAL"
        is ContextIntent.HypoRecovery -> "HYPO_RECOVERY"
        is ContextIntent.Stress -> "STRESS"
        is ContextIntent.MenstrualCycle -> "MENSTRUAL_CYCLE"
        is ContextIntent.Travel -> "TRAVEL"
        is ContextIntent.Alcohol -> "ALCOHOL"
        is ContextIntent.Custom -> "CUSTOM"
    }
}
