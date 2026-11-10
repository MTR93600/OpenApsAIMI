package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalHypothesis
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PatientStatePresentationBuilderTest {

    @Test
    fun build_formats_patient_mode_story_for_clinical_ui() {
        val presentation = PatientStatePresentationBuilder.build(
            snapshot = PatientRuntimeSnapshot(
                patientState = PatientStateSnapshot(
                    timestampMs = 1_718_000_000_000L,
                    phase = PhysiologicalPhase.DAWN_CORTISOL,
                    phaseConfidence = 0.84,
                    mealAbsorptionPhase = MealAbsorptionPhase.NONE,
                    mealProb = 0.22,
                    endogenousGlucoseDrive = 0.88,
                    transientResistanceProb = 0.52,
                    sensorConfidence = 0.86,
                    falseMealSuppression = true,
                    uamDominant = UamHypothesisId.DAWN_ENDOGENOUS,
                    uamDominantConfidence = 0.88,
                    userIntent = UserIntentSummary(
                        enabled = true,
                        intentCount = 1,
                        avgConfidence = 0.82,
                        hasStress = true,
                        dominantIntent = "STRESS",
                    ),
                    causalPosterior = CausalStatePosterior(
                        dawnEndogenousProb = 0.88,
                        postHypoRecoveryProb = 0.22,
                        stressResistanceProb = 0.30,
                        dominant = CausalStateId.DAWN_ENDOGENOUS,
                        dominantConfidence = 0.88,
                        learningQuality = 0.62,
                    ),
                ),
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.DAWN_ENDOGENOUS,
                    confidence = 0.88,
                    strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                    mealBias = 0.16,
                    protectionBias = 0.78,
                    userIntentConfidence = 0.82,
                    reasonCodes = listOf("LATENT_ENDOGENOUS", "FALSE_MEAL_SUPPRESS"),
                ),
                thermalBelief = ThermalBeliefDigest(
                    hypothesis = ThermalHypothesis.BASELINE_STABLE,
                    deltaVsBaselineC = 0.05,
                    confidence = 0.7,
                    narrative = "Skin temperature rhythm is stable around your personal baseline.",
                ),
                physiologicalTree = PhysiologicalTreeBuilder.build(
                    enabled = true,
                    patientState = PatientStateSnapshot(
                        timestampMs = 1_718_000_000_000L,
                        phase = PhysiologicalPhase.DAWN_CORTISOL,
                        phaseConfidence = 0.84,
                        mealAbsorptionPhase = MealAbsorptionPhase.NONE,
                        mealProb = 0.22,
                        endogenousGlucoseDrive = 0.88,
                        transientResistanceProb = 0.52,
                        sensorConfidence = 0.86,
                        falseMealSuppression = true,
                        uamDominant = UamHypothesisId.DAWN_ENDOGENOUS,
                        uamDominantConfidence = 0.88,
                        causalPosterior = CausalStatePosterior(
                            dawnEndogenousProb = 0.88,
                            stressResistanceProb = 0.30,
                            dominant = CausalStateId.DAWN_ENDOGENOUS,
                            dominantConfidence = 0.88,
                            learningQuality = 0.62,
                        ),
                    ),
                    patientModeDecision = PatientModeOrchestrator.Decision(
                        mode = PatientMode.DAWN_ENDOGENOUS,
                        confidence = 0.88,
                        strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                        mealBias = 0.16,
                        protectionBias = 0.78,
                        userIntentConfidence = 0.82,
                        reasonCodes = listOf("LATENT_ENDOGENOUS", "FALSE_MEAL_SUPPRESS"),
                    ),
                ),
                harmoniaDecision = HarmoniaDecisionEngine.evaluate(
                    tree = PhysiologicalTreeBuilder.build(
                        enabled = true,
                        patientState = PatientStateSnapshot(
                            timestampMs = 1_718_000_000_000L,
                            phase = PhysiologicalPhase.DAWN_CORTISOL,
                            phaseConfidence = 0.84,
                            mealAbsorptionPhase = MealAbsorptionPhase.NONE,
                            mealProb = 0.22,
                            endogenousGlucoseDrive = 0.88,
                            transientResistanceProb = 0.52,
                            sensorConfidence = 0.86,
                            falseMealSuppression = true,
                            uamDominant = UamHypothesisId.DAWN_ENDOGENOUS,
                            uamDominantConfidence = 0.88,
                            causalPosterior = CausalStatePosterior(
                                dawnEndogenousProb = 0.88,
                                stressResistanceProb = 0.30,
                                dominant = CausalStateId.DAWN_ENDOGENOUS,
                                dominantConfidence = 0.88,
                                learningQuality = 0.62,
                            ),
                        ),
                        patientModeDecision = PatientModeOrchestrator.Decision(
                            mode = PatientMode.DAWN_ENDOGENOUS,
                            confidence = 0.88,
                            strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                            mealBias = 0.16,
                            protectionBias = 0.78,
                            userIntentConfidence = 0.82,
                            reasonCodes = listOf("LATENT_ENDOGENOUS", "FALSE_MEAL_SUPPRESS"),
                        ),
                    ),
                    environment = HarmoniaDecisionEnvironment(
                        currentBgMgdl = 180.0,
                        deltaMgdl5m = 2.0,
                        iobU = 1.0,
                        cobG = 0.0,
                        currentBasalUph = 1.0,
                        maxBasalUph = 5.0,
                        maxSmbU = 1.0,
                        maxIobU = 5.0,
                    ),
                ),
                updatedAtMs = 1_718_000_000_000L,
            ),
            nowMs = 1_718_000_180_000L,
        )

        assertThat(presentation.updatedSummary).isEqualTo("Updated 3 minutes ago")
        assertThat(presentation.modeHeadline).isEqualTo("Dawn Endogenous (88%)")
        assertThat(presentation.narrative).contains("endogenous drive")
        assertThat(presentation.physiologySummary).contains("Dawn Cortisol")
        assertThat(presentation.physiologySummary).contains("Cause Dawn Endogenous")
        assertThat(presentation.physiologySummary).contains("Tree:")
        assertThat(presentation.physiologySummary).contains("Harmonia sim:")
        assertThat(presentation.intentSummary).contains("Dominant Stress")
        assertThat(presentation.signalSummary).contains("Endogenous 88%")
        assertThat(presentation.signalSummary).contains("Protect 88%")
        assertThat(presentation.deliverySummary).contains("Basal Bridge")
        assertThat(presentation.reasonSummary).contains("False meal suppression")
        assertThat(presentation.physioLiveSummary).contains("Body signals pending")
        assertThat(presentation.thermalSummary).contains("baseline")
        assertThat(presentation.signalGauges).hasSize(5)
        assertThat(presentation.signalGauges[1].label).isEqualTo("Endogenous")
        assertThat(presentation.signalGauges[1].percent).isEqualTo(88)
    }
}
