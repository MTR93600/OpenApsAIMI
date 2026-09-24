package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PatientModeOrchestratorTest {

    @Test
    fun evaluate_prefers_causal_fast_meal_when_posterior_is_decisive() {
        val decision = PatientModeOrchestrator.evaluate(
            PatientStateSnapshot(
                timestampMs = 1_718_000_000_000L,
                phase = PhysiologicalPhase.OFF,
                mealAbsorptionPhase = MealAbsorptionPhase.NONE,
                mealProb = 0.34,
                endogenousGlucoseDrive = 0.18,
                transientResistanceProb = 0.10,
                sensorConfidence = 0.90,
                causalPosterior = CausalStatePosterior(
                    fastMealProb = 0.79,
                    prolongedMealProb = 0.18,
                    dawnEndogenousProb = 0.12,
                    postHypoRecoveryProb = 0.06,
                    stressResistanceProb = 0.08,
                    exerciseAfterburnProb = 0.04,
                    inflammatoryDriftProb = 0.05,
                    absorptionUncertainProb = 0.10,
                    dominant = CausalStateId.FAST_MEAL,
                    dominantConfidence = 0.79,
                    learningQuality = 0.82,
                ),
            ),
        )

        assertThat(decision.mode).isEqualTo(PatientMode.FAST_MEAL)
        assertThat(decision.reasonCodes).contains("CAUSAL_FAST_MEAL")
        assertThat(decision.mealBias).isGreaterThan(0.85)
    }

    @Test
    fun evaluate_prefers_dawn_endogenous_when_endogenous_drive_dominates() {
        val decision = PatientModeOrchestrator.evaluate(
            PatientStateSnapshot(
                timestampMs = 1_718_000_000_000L,
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                phaseConfidence = 0.78,
                mealAbsorptionPhase = MealAbsorptionPhase.NONE,
                mealProb = 0.18,
                endogenousGlucoseDrive = 0.84,
                transientResistanceProb = 0.42,
                postHypoReboundProb = 0.08,
                sensorConfidence = 0.90,
                falseMealSuppression = true,
                uamDominant = UamHypothesisId.DAWN_ENDOGENOUS,
                uamDominantConfidence = 0.82,
            ),
        )

        assertThat(decision.mode).isEqualTo(PatientMode.DAWN_ENDOGENOUS)
        assertThat(decision.strategyHint).isEqualTo(PatientStrategyHint.BASAL_BRIDGE)
        assertThat(decision.protectionBias).isGreaterThan(0.70)
    }

    @Test
    fun evaluate_selects_fast_meal_for_first_wave_context() {
        val decision = PatientModeOrchestrator.evaluate(
            PatientStateSnapshot(
                timestampMs = 1_718_000_000_000L,
                phase = PhysiologicalPhase.MEAL_UNDECLARED,
                phaseConfidence = 0.84,
                mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                mealAbsorptionBelief = 0.88,
                mealProb = 0.86,
                endogenousGlucoseDrive = 0.12,
                transientResistanceProb = 0.18,
                sensorConfidence = 0.95,
                uamDominant = UamHypothesisId.MEAL,
                uamDominantConfidence = 0.86,
                userIntent = UserIntentSummary(
                    enabled = true,
                    intentCount = 1,
                    avgConfidence = 0.90,
                    hasMealRisk = true,
                    dominantIntent = "MEAL_RISK",
                ),
            ),
        )

        assertThat(decision.mode).isEqualTo(PatientMode.FAST_MEAL)
        assertThat(decision.strategyHint).isEqualTo(PatientStrategyHint.SMB_PRIORITY)
        assertThat(decision.mealBias).isGreaterThan(0.80)
    }

    @Test
    fun evaluate_preempts_meal_branch_when_false_meal_suppression_and_protective_context_dominate() {
        val decision = PatientModeOrchestrator.evaluate(
            PatientStateSnapshot(
                timestampMs = 1_718_000_000_000L,
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                phaseConfidence = 0.81,
                mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                mealAbsorptionBelief = 0.78,
                mealProb = 0.80,
                endogenousGlucoseDrive = 0.76,
                transientResistanceProb = 0.34,
                falseMealSuppression = true,
                sensorConfidence = 0.93,
                causalPosterior = CausalStatePosterior(
                    fastMealProb = 0.56,
                    prolongedMealProb = 0.18,
                    dawnEndogenousProb = 0.84,
                    postHypoRecoveryProb = 0.10,
                    stressResistanceProb = 0.14,
                    exerciseAfterburnProb = 0.06,
                    inflammatoryDriftProb = 0.08,
                    absorptionUncertainProb = 0.20,
                    dominant = CausalStateId.DAWN_ENDOGENOUS,
                    dominantConfidence = 0.84,
                    learningQuality = 0.74,
                ),
            ),
        )

        assertThat(decision.mode).isEqualTo(PatientMode.DAWN_ENDOGENOUS)
        assertThat(decision.reasonCodes).contains("PROTECTIVE_PREEMPT")
        assertThat(decision.reasonCodes).contains("FALSE_MEAL_SUPPRESS")
    }
}
