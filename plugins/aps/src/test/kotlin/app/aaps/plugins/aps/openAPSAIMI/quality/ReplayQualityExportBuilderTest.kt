package app.aaps.plugins.aps.openAPSAIMI.quality

import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStrategyHint
import app.aaps.plugins.aps.openAPSAIMI.patient.UserIntentSummary
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternReading
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefAuthorityGate
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefPreferences
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskPhase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReplayQualityExportBuilderTest {

    @Test
    fun build_marks_false_meal_and_post_hypo_guards_from_existing_signals() {
        val export = ReplayQualityExportBuilder.build(
            phaseOutput = PhysiologicalPhaseClassifier.Output(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.84,
                policy = BehavioralRiskPolicy.forPhase(
                    phase = PhysiologicalPhase.DAWN_CORTISOL,
                    confidence = 0.84,
                    reason = "dawn near target",
                ),
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.NONE,
                belief = 0.18,
                reason = "suppressed by dawn",
                deltaMgdlPer5 = 1.2,
                gapMgdl = 14.0,
                bestTerminalMgdl = 142.0,
                memoryActive = false,
                waveCount = 0,
                mealDeliveryPriority = false,
                chronoPrior = 0.22,
                kineticScore = 0.20,
                trajectoryScore = 0.18,
                physioScore = 0.42,
            ),
            hypothesisState = UamHypothesisState(
                mealProb = 0.22,
                dawnEndogenousProb = 0.88,
                stressProb = 0.10,
                postHypoProb = 0.78,
                dominant = UamHypothesisId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.88,
                suppressMealInterpretation = true,
            ),
            patientState = PatientStateSnapshot(
                timestampMs = 1_718_000_000_000L,
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                phaseConfidence = 0.84,
                mealProb = 0.22,
                endogenousGlucoseDrive = 0.88,
                transientResistanceProb = 0.52,
                postHypoReboundProb = 0.78,
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
                    fastMealProb = 0.18,
                    prolongedMealProb = 0.08,
                    dawnEndogenousProb = 0.89,
                    postHypoRecoveryProb = 0.74,
                    stressResistanceProb = 0.48,
                    exerciseAfterburnProb = 0.05,
                    inflammatoryDriftProb = 0.26,
                    absorptionUncertainProb = 0.21,
                    dominant = CausalStateId.DAWN_ENDOGENOUS,
                    dominantConfidence = 0.89,
                    learningQuality = 0.44,
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
            patternSnapshot = PhysiologicalPatternSnapshot(
                active = listOf(
                    PhysiologicalPatternReading(
                        id = PhysiologicalPatternId.DAWN_CORTISOL,
                        confidence = 0.88,
                        reason = "dawn guard",
                    ),
                    PhysiologicalPatternReading(
                        id = PhysiologicalPatternId.POST_HYPO_REBOUND,
                        confidence = 0.73,
                        reason = "recent low",
                    ),
                ),
                dominant = PhysiologicalPatternId.DAWN_CORTISOL,
                dominantConfidence = 0.88,
                suppressMealInterpretation = true,
                suppressHyperRelease = true,
                suppressWaveletBoost = true,
                smbCapU = 0.50,
                reasonSummary = "dawn + rebound",
            ),
            iobSurveillanceExport = null,
            safetyRiskExport = SafetyRiskExportSnapshot(
                phase = AimiRiskPhase.DECISION,
                predictiveHypoSuppressed = true,
                safetyGate = "predictive_hypo_guard",
                haltRemainingPipeline = false,
                mealContextActive = false,
                mealRiseConfirmed = false,
                compositeMinMgdl = 78.0,
                predBgMgdl = 92.0,
                eventualBgMgdl = 95.0,
                uamTerminalMgdl = 150.0,
                hypoThresholdMgdl = 75.0,
            ),
            recursiveBeliefSnapshot = null,
            authorityGateDecision = RecursiveBeliefAuthorityGate.Decision(
                requestedAuthority = ReleaseAuthority.HARD,
                maxAllowedAuthority = ReleaseAuthority.NONE,
                effectiveAuthority = ReleaseAuthority.NONE,
                readinessScore = 0.18,
                liftBlend = 0.0,
                reasonCodes = listOf("PRED_MISSING", "PREDICTIVE_HYPO"),
            ),
            correctionAggressionDecision = CorrectionAggressionGate.Decision(
                tier = CorrectionAggressionGate.Tier.REBOUND_GUARD,
                mealTierFull = false,
                allowGlobalHyperKicker = false,
                allowRocketBasalScale = false,
                allowRocketHypoOverride = false,
                maxBasalScaleCap = 1.5,
                reasonTag = "post_hypo_rebound_guard",
            ),
            predictionAvailable = false,
            smbProposedU = 0.90,
            smbCappedU = 0.50,
            smbFinalU = 0.30,
            decisionSource = "AIMI",
            safetySource = "IOB_SURVEILLANCE",
            rbtPreferences = RecursiveBeliefPreferences(
                shadowEnabled = true,
                authorityEnabled = true,
                waveletEnabled = true,
                mealHyperBypassEnabled = true,
                treeMealRiseFrontLoadEnabled = true,
            ),
        )

        assertThat(export.falseMealGuardState).isEqualTo("SUPPRESS_DAWN_CORTISOL")
        assertThat(export.uamHypothesisDominant).isEqualTo("DAWN_ENDOGENOUS")
        assertThat(export.uamMealInterpretationSuppressed).isTrue()
        assertThat(export.mealInterpretationSuppressed).isTrue()
        assertThat(export.patientMode).isEqualTo("DAWN_ENDOGENOUS")
        assertThat(export.patientStrategyHint).isEqualTo("BASAL_BRIDGE")
        assertThat(export.causalStateDominant).isEqualTo("DAWN_ENDOGENOUS")
        assertThat(export.causalLearningQuality).isWithin(1e-9).of(0.44)
        assertThat(export.postHypoGuardState).isEqualTo("CORRECTION_REBOUND_GUARD")
        assertThat(export.predictiveHypoSuppressed).isTrue()
        assertThat(export.rbtMode).isEqualTo("GATE_BLOCKED")
        assertThat(export.authorityRequested).isEqualTo("HARD")
        assertThat(export.authorityEffective).isEqualTo("NONE")
        assertThat(export.qualityTags).contains("meal_interpretation_suppressed")
        assertThat(export.qualityTags).contains("uam_multi_hypothesis_guard")
        assertThat(export.qualityTags).contains("post_hypo_guard_active")
        assertThat(export.qualityTags).contains("prediction_missing")
        assertThat(export.qualityTags).contains("rbt_authority_blocked")
        assertThat(export.qualityTags).contains("user_intent_active")
        assertThat(export.qualityTags).contains("causal_dawn_endogenous")
        assertThat(export.qualityTags).contains("causal_learning_unclean")
        assertThat(export.qualityTags).contains("patient_mode_dawn_endogenous")
    }

    @Test
    fun build_marks_meal_and_stacking_channels_when_active() {
        val export = ReplayQualityExportBuilder.build(
            phaseOutput = PhysiologicalPhaseClassifier.Output(
                phase = PhysiologicalPhase.MEAL_UNDECLARED,
                confidence = 0.86,
                policy = BehavioralRiskPolicy.forPhase(
                    phase = PhysiologicalPhase.MEAL_UNDECLARED,
                    confidence = 0.86,
                    reason = "meal-like gap",
                ),
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.91,
                reason = "fast rise",
                deltaMgdlPer5 = 4.5,
                gapMgdl = 58.0,
                bestTerminalMgdl = 238.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.85,
                kineticScore = 0.82,
                trajectoryScore = 0.74,
                physioScore = 0.22,
            ),
            hypothesisState = UamHypothesisState(
                mealProb = 0.90,
                dawnEndogenousProb = 0.08,
                stressProb = 0.05,
                postHypoProb = 0.02,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.90,
                suppressMealInterpretation = false,
            ),
            patientState = PatientStateSnapshot(
                timestampMs = 1_718_000_000_000L,
                phase = PhysiologicalPhase.MEAL_UNDECLARED,
                phaseConfidence = 0.86,
                mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                mealAbsorptionBelief = 0.91,
                mealProb = 0.90,
                endogenousGlucoseDrive = 0.08,
                transientResistanceProb = 0.05,
                sensorConfidence = 0.96,
                uamDominant = UamHypothesisId.MEAL,
                uamDominantConfidence = 0.90,
                userIntent = UserIntentSummary(
                    enabled = true,
                    intentCount = 1,
                    avgConfidence = 0.90,
                    hasMealRisk = true,
                    dominantIntent = "MEAL_RISK",
                ),
                causalPosterior = CausalStatePosterior(
                    fastMealProb = 0.92,
                    prolongedMealProb = 0.18,
                    dawnEndogenousProb = 0.06,
                    postHypoRecoveryProb = 0.04,
                    stressResistanceProb = 0.05,
                    exerciseAfterburnProb = 0.03,
                    inflammatoryDriftProb = 0.02,
                    absorptionUncertainProb = 0.08,
                    dominant = CausalStateId.FAST_MEAL,
                    dominantConfidence = 0.92,
                    learningQuality = 0.88,
                ),
            ),
            patientModeDecision = PatientModeOrchestrator.Decision(
                mode = PatientMode.FAST_MEAL,
                confidence = 0.91,
                strategyHint = PatientStrategyHint.SMB_PRIORITY,
                mealBias = 0.90,
                protectionBias = 0.18,
                userIntentConfidence = 0.90,
                reasonCodes = listOf("MEAL_FIRST_WAVE", "UAM_MEAL"),
            ),
            patternSnapshot = PhysiologicalPatternSnapshot(
                active = listOf(
                    PhysiologicalPatternReading(
                        id = PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
                        confidence = 0.79,
                        reason = "recent corrections",
                    ),
                ),
                dominant = PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
                dominantConfidence = 0.79,
                suppressMealInterpretation = false,
                suppressHyperRelease = false,
                suppressWaveletBoost = false,
                smbCapU = 0.70,
                reasonSummary = "stacking",
            ),
            iobSurveillanceExport = null,
            safetyRiskExport = null,
            recursiveBeliefSnapshot = null,
            authorityGateDecision = null,
            correctionAggressionDecision = CorrectionAggressionGate.Decision(
                tier = CorrectionAggressionGate.Tier.FULL,
                mealTierFull = true,
                allowGlobalHyperKicker = true,
                allowRocketBasalScale = true,
                allowRocketHypoOverride = true,
                maxBasalScaleCap = 10.0,
                reasonTag = "meal_or_hyper_full",
            ),
            predictionAvailable = true,
            smbProposedU = 1.20,
            smbCappedU = 0.80,
            smbFinalU = 0.80,
            decisionSource = "AUTODRIVE_V3",
            safetySource = "RECURSIVE_BELIEF",
            rbtPreferences = RecursiveBeliefPreferences(
                shadowEnabled = false,
                authorityEnabled = false,
                waveletEnabled = false,
                mealHyperBypassEnabled = false,
                treeMealRiseFrontLoadEnabled = false,
            ),
        )

        assertThat(export.mealHypothesisState).isEqualTo("FIRST_WAVE")
        assertThat(export.mealHypothesisConfidence).isWithin(1e-9).of(0.91)
        assertThat(export.uamHypothesisDominant).isEqualTo("MEAL")
        assertThat(export.patientMode).isEqualTo("FAST_MEAL")
        assertThat(export.causalStateDominant).isEqualTo("FAST_MEAL")
        assertThat(export.stackingGuardState).isEqualTo("PATTERN_IOB_STACKING_SURVEILLANCE")
        assertThat(export.stackingGuardActive).isTrue()
        assertThat(export.qualityTags).contains("causal_fast_meal")
        assertThat(export.qualityTags).contains("meal_hypothesis_active")
        assertThat(export.qualityTags).contains("stacking_guard_active")
        assertThat(export.qualityTags).contains("patient_mode_meal")
        assertThat(export.rbtMode).isEqualTo("OFF")
    }
}
