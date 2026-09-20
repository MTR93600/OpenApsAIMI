package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStrategyHint
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealCorrectionContextResolverTest {

    @Test
    fun `undeclared fast meal enables meal priority and red carpet`() {
        val output = MealCorrectionContextResolver.resolve(
            MealCorrectionContextResolver.Input(
                bgMgdl = 198.0,
                deltaMgdlPer5 = 2.9,
                shortAvgDeltaMgdlPer5 = 2.3,
                explicitMealMode = false,
                mealCobG = 0.0,
                mealAbsorptionOutput = mealAbsorptionOutput(
                    phase = MealAbsorptionPhase.FIRST_WAVE,
                    belief = 0.74,
                    mealDeliveryPriority = true,
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.68,
                    dominant = UamHypothesisId.MEAL,
                    dominantConfidence = 0.70,
                ),
                latentState = PhysioLatentState(
                    mealProb = 0.79,
                    falseMealSuppression = false,
                ),
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.FAST_MEAL,
                    confidence = 0.78,
                    strategyHint = PatientStrategyHint.SMB_PRIORITY,
                    mealBias = 0.91,
                    protectionBias = 0.14,
                    userIntentConfidence = 0.0,
                    reasonCodes = listOf("FAST_MEAL"),
                ),
                patientState = PatientStateSnapshot(
                    timestampMs = 0L,
                    mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                    mealAbsorptionBelief = 0.74,
                    mealProb = 0.81,
                    falseMealSuppression = false,
                    uamDominant = UamHypothesisId.MEAL,
                    uamDominantConfidence = 0.70,
                    causalPosterior = CausalStatePosterior(
                        fastMealProb = 0.79,
                        dominant = CausalStateId.FAST_MEAL,
                        dominantConfidence = 0.79,
                        learningQuality = 0.82,
                    ),
                ),
            ),
        )

        assertThat(output.mealPriorityEligible).isTrue()
        assertThat(output.redCarpetEligible).isTrue()
        assertThat(output.reasonCodes).contains("MEAL_DELIVERY_PRIORITY")
        assertThat(output.reasonCodes).contains("RED_CARPET_ELIGIBLE")
    }

    @Test
    fun `endogenous or cortisol competition blocks false undeclared meal`() {
        val output = MealCorrectionContextResolver.resolve(
            MealCorrectionContextResolver.Input(
                bgMgdl = 174.0,
                deltaMgdlPer5 = 1.9,
                shortAvgDeltaMgdlPer5 = 1.7,
                explicitMealMode = false,
                mealCobG = 0.0,
                mealAbsorptionOutput = mealAbsorptionOutput(
                    phase = MealAbsorptionPhase.NONE,
                    belief = 0.12,
                    mealDeliveryPriority = false,
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.44,
                    dawnEndogenousProb = 0.79,
                    stressProb = 0.72,
                    dominant = UamHypothesisId.DAWN_ENDOGENOUS,
                    dominantConfidence = 0.79,
                ),
                latentState = PhysioLatentState(
                    mealProb = 0.46,
                    falseMealSuppression = false,
                ),
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.DAWN_ENDOGENOUS,
                    confidence = 0.77,
                    strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                    mealBias = 0.18,
                    protectionBias = 0.88,
                    userIntentConfidence = 0.0,
                    reasonCodes = listOf("DAWN_ENDOGENOUS"),
                ),
                patientState = PatientStateSnapshot(
                    timestampMs = 0L,
                    mealProb = 0.42,
                    falseMealSuppression = false,
                    uamDominant = UamHypothesisId.DAWN_ENDOGENOUS,
                    uamDominantConfidence = 0.79,
                    causalPosterior = CausalStatePosterior(
                        dawnEndogenousProb = 0.82,
                        stressResistanceProb = 0.74,
                        dominant = CausalStateId.DAWN_ENDOGENOUS,
                        dominantConfidence = 0.82,
                        learningQuality = 0.80,
                    ),
                ),
            ),
        )

        assertThat(output.mealPriorityEligible).isFalse()
        assertThat(output.redCarpetEligible).isFalse()
        assertThat(output.reasonCodes).contains("NON_MEAL_BLOCK")
    }

    @Test
    fun `false meal suppression wins even when trajectory looks meal like`() {
        val output = MealCorrectionContextResolver.resolve(
            MealCorrectionContextResolver.Input(
                bgMgdl = 186.0,
                deltaMgdlPer5 = 2.6,
                shortAvgDeltaMgdlPer5 = 2.1,
                explicitMealMode = false,
                mealCobG = 0.0,
                mealAbsorptionOutput = mealAbsorptionOutput(
                    phase = MealAbsorptionPhase.FIRST_WAVE,
                    belief = 0.71,
                    mealDeliveryPriority = true,
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.58,
                    stressProb = 0.76,
                    dominant = UamHypothesisId.STRESS,
                    dominantConfidence = 0.76,
                    suppressMealInterpretation = true,
                ),
                latentState = PhysioLatentState(
                    mealProb = 0.72,
                    falseMealSuppression = true,
                ),
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.STRESS_RESISTANCE,
                    confidence = 0.80,
                    strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                    mealBias = 0.24,
                    protectionBias = 0.86,
                    userIntentConfidence = 0.0,
                    reasonCodes = listOf("STRESS_RESISTANCE"),
                ),
                patientState = PatientStateSnapshot(
                    timestampMs = 0L,
                    mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                    mealAbsorptionBelief = 0.71,
                    mealProb = 0.74,
                    falseMealSuppression = true,
                    uamDominant = UamHypothesisId.STRESS,
                    uamDominantConfidence = 0.76,
                    causalPosterior = CausalStatePosterior(
                        stressResistanceProb = 0.81,
                        dominant = CausalStateId.STRESS_RESISTANCE,
                        dominantConfidence = 0.81,
                        learningQuality = 0.78,
                    ),
                ),
            ),
        )

        assertThat(output.falseMealSuppression).isTrue()
        assertThat(output.mealPriorityEligible).isFalse()
        assertThat(output.redCarpetEligible).isFalse()
        assertThat(output.reasonCodes).contains("FALSE_MEAL_SUPPRESS")
    }

    @Test
    fun `post hypo delivery guard blocks fast meal rebound rise`() {
        val postHypo = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = CorrectionAggressionGate.evaluate(
                    CorrectionAggressionGate.Input(
                        bg = 119.0,
                        targetBg = 100.0,
                        deltaMgdl5m = 5.0,
                        shortAvgDelta = 4.0,
                        combinedDelta = 2.5,
                        cob = 0.0,
                        minBgLookback75m = 54.0,
                        estimatedCarbs = 0.0,
                        estimatedCarbsAgeMin = Double.MAX_VALUE,
                        uamConfidence = 0.7,
                        estimatedRa = 0.9,
                        explicitMealMode = false,
                        hasRecentMealEstimate = false,
                        isConfirmedHighRise = false,
                    ),
                ),
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = CorrectionAggressionGate.Input(
                    bg = 119.0,
                    targetBg = 100.0,
                    deltaMgdl5m = 5.0,
                    shortAvgDelta = 4.0,
                    combinedDelta = 2.5,
                    cob = 0.0,
                    minBgLookback75m = 54.0,
                    estimatedCarbs = 0.0,
                    estimatedCarbsAgeMin = Double.MAX_VALUE,
                    uamConfidence = 0.7,
                    estimatedRa = 0.9,
                    explicitMealMode = false,
                    hasRecentMealEstimate = false,
                    isConfirmedHighRise = false,
                ),
            ),
        )

        val output = MealCorrectionContextResolver.resolve(
            MealCorrectionContextResolver.Input(
                bgMgdl = 131.0,
                deltaMgdlPer5 = 5.0,
                shortAvgDeltaMgdlPer5 = 4.0,
                explicitMealMode = false,
                mealCobG = 0.0,
                mealAbsorptionOutput = mealAbsorptionOutput(
                    phase = MealAbsorptionPhase.FIRST_WAVE,
                    belief = 0.74,
                    mealDeliveryPriority = true,
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.68,
                    dominant = UamHypothesisId.MEAL,
                    dominantConfidence = 0.70,
                ),
                latentState = PhysioLatentState(
                    mealProb = 0.79,
                    falseMealSuppression = false,
                ),
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.FAST_MEAL,
                    confidence = 0.78,
                    strategyHint = PatientStrategyHint.SMB_PRIORITY,
                    mealBias = 0.91,
                    protectionBias = 0.14,
                    userIntentConfidence = 0.0,
                    reasonCodes = listOf("FAST_MEAL"),
                ),
                patientState = PatientStateSnapshot(
                    timestampMs = 0L,
                    mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                    mealAbsorptionBelief = 0.74,
                    mealProb = 0.81,
                    falseMealSuppression = false,
                    uamDominant = UamHypothesisId.MEAL,
                    uamDominantConfidence = 0.70,
                    causalPosterior = CausalStatePosterior(
                        fastMealProb = 0.79,
                        dominant = CausalStateId.FAST_MEAL,
                        dominantConfidence = 0.79,
                        learningQuality = 0.82,
                    ),
                ),
                postHypoDelivery = postHypo,
            ),
        )

        assertThat(postHypo.active).isTrue()
        assertThat(output.falseMealSuppression).isTrue()
        assertThat(output.mealPriorityEligible).isFalse()
        assertThat(output.redCarpetEligible).isFalse()
        assertThat(output.reasonCodes).contains("POST_HYPO_DELIVERY_GUARD")
        assertThat(output.reasonCodes).contains("FALSE_MEAL_SUPPRESS")
    }

    private fun mealAbsorptionOutput(
        phase: MealAbsorptionPhase,
        belief: Double,
        mealDeliveryPriority: Boolean,
    ): MealAbsorptionPhaseEngine.Output =
        MealAbsorptionPhaseEngine.Output(
            phase = phase,
            belief = belief,
            reason = phase.name,
            deltaMgdlPer5 = 0.0,
            gapMgdl = 0.0,
            bestTerminalMgdl = 0.0,
            memoryActive = phase.isActive,
            waveCount = if (phase.isActive) 1 else 0,
            mealDeliveryPriority = mealDeliveryPriority,
            chronoPrior = 0.0,
            kineticScore = 0.0,
            trajectoryScore = 0.0,
            physioScore = 0.0,
        )
}
