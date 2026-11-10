package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternReading
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UamHypothesisStateBuilderTest {

    @Test
    fun dawn_endogenous_dominates_and_suppresses_meal_when_false_meal_guard_is_active() {
        val hypotheses = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.86,
                reason = "dawn near target",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.74,
                reason = "fast rise",
                deltaMgdlPer5 = 3.2,
                gapMgdl = 42.0,
                bestTerminalMgdl = 198.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.18,
                kineticScore = 0.70,
                trajectoryScore = 0.66,
                physioScore = 0.18,
            ),
            patternSnapshot = patternSnapshot(
                suppressMealInterpretation = true,
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.DAWN_CORTISOL,
                    confidence = 0.88,
                    reason = "dawn guard",
                ),
            ),
            correctionAggressionDecision = null,
            uamConfidence = 0.62,
        )

        assertThat(hypotheses.dominant).isEqualTo(UamHypothesisId.DAWN_ENDOGENOUS)
        assertThat(hypotheses.suppressMealInterpretation).isTrue()
        assertThat(hypotheses.mealProb).isAtMost(0.32)
        assertThat(hypotheses.dawnEndogenousProb).isGreaterThan(hypotheses.mealProb)
    }

    @Test
    fun late_fat_stays_meal_compatible_while_post_hypo_remains_separate() {
        val hypotheses = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.OFF,
                confidence = 0.50,
                reason = "none",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.LATE_FAT,
                belief = 0.68,
                reason = "late fat",
                deltaMgdlPer5 = 1.5,
                gapMgdl = 28.0,
                bestTerminalMgdl = 182.0,
                memoryActive = true,
                waveCount = 2,
                mealDeliveryPriority = false,
                chronoPrior = 0.42,
                kineticScore = 0.32,
                trajectoryScore = 0.40,
                physioScore = 0.12,
            ),
            patternSnapshot = patternSnapshot(
                suppressMealInterpretation = false,
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.LATE_FAT_PROTEIN,
                    confidence = 0.72,
                    reason = "late fat protein",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.POST_HYPO_REBOUND,
                    confidence = 0.55,
                    reason = "rebound",
                ),
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
            uamConfidence = 0.35,
        )

        assertThat(hypotheses.dominant).isEqualTo(UamHypothesisId.LATE_FAT)
        assertThat(hypotheses.suppressMealInterpretation).isFalse()
        assertThat(hypotheses.mealCompatibleProb()).isAtLeast(0.60)
        assertThat(hypotheses.postHypoProb).isGreaterThan(0.70)
    }

    @Test
    fun cautious_meal_profile_strengthens_false_meal_guard_under_endogenous_competition() {
        val defaultHypotheses = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.80,
                reason = "dawn competition",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.59,
                reason = "moderate meal-like rise",
                deltaMgdlPer5 = 2.8,
                gapMgdl = 30.0,
                bestTerminalMgdl = 176.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = false,
                chronoPrior = 0.20,
                kineticScore = 0.58,
                trajectoryScore = 0.55,
                physioScore = 0.26,
            ),
            patternSnapshot = null,
            correctionAggressionDecision = null,
            uamConfidence = 0.0,
        )
        val cautiousHypotheses = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.80,
                reason = "dawn competition",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.59,
                reason = "moderate meal-like rise",
                deltaMgdlPer5 = 2.8,
                gapMgdl = 30.0,
                bestTerminalMgdl = 176.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = false,
                chronoPrior = 0.20,
                kineticScore = 0.58,
                trajectoryScore = 0.55,
                physioScore = 0.26,
            ),
            patternSnapshot = null,
            correctionAggressionDecision = null,
            uamConfidence = 0.0,
            behaviorProfile = AimiBehaviorRuntimeProfile(
                protectionLevel = 1,
                mealCaptureLevel = 1,
                stabilityLevel = 2,
                physioLevel = 1,
                autonomyMode = AimiAutonomyMode.Recommendations,
            ),
        )

        assertThat(defaultHypotheses.suppressMealInterpretation).isFalse()
        assertThat(cautiousHypotheses.suppressMealInterpretation).isTrue()
        assertThat(cautiousHypotheses.mealProb).isLessThan(defaultHypotheses.mealProb)
    }

    @Test
    fun assertive_family_profile_keeps_meal_interpretation_when_competition_is_ambiguous() {
        val defaultHypotheses = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.86,
                reason = "ambiguous dawn vs meal",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.60,
                reason = "strong meal-like rise",
                deltaMgdlPer5 = 3.1,
                gapMgdl = 36.0,
                bestTerminalMgdl = 190.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.24,
                kineticScore = 0.64,
                trajectoryScore = 0.62,
                physioScore = 0.22,
            ),
            patternSnapshot = null,
            correctionAggressionDecision = null,
            uamConfidence = 0.0,
        )
        val assertiveHypotheses = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.86,
                reason = "ambiguous dawn vs meal",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.60,
                reason = "strong meal-like rise",
                deltaMgdlPer5 = 3.1,
                gapMgdl = 36.0,
                bestTerminalMgdl = 190.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.24,
                kineticScore = 0.64,
                trajectoryScore = 0.62,
                physioScore = 0.22,
            ),
            patternSnapshot = null,
            correctionAggressionDecision = null,
            uamConfidence = 0.0,
            behaviorProfile = AimiBehaviorRuntimeProfile(
                protectionLevel = 2,
                mealCaptureLevel = 4,
                stabilityLevel = 3,
                physioLevel = 1,
                autonomyMode = AimiAutonomyMode.ControlledAuthority,
            ),
        )

        assertThat(defaultHypotheses.suppressMealInterpretation).isTrue()
        assertThat(assertiveHypotheses.suppressMealInterpretation).isFalse()
        assertThat(assertiveHypotheses.mealProb).isGreaterThan(defaultHypotheses.mealProb)
    }

    private fun phaseOutput(
        phase: PhysiologicalPhase,
        confidence: Double,
        reason: String,
    ): PhysiologicalPhaseClassifier.Output =
        PhysiologicalPhaseClassifier.Output(
            phase = phase,
            confidence = confidence,
            policy = BehavioralRiskPolicy.forPhase(
                phase = phase,
                confidence = confidence,
                reason = reason,
            ),
        )

    private fun patternSnapshot(
        suppressMealInterpretation: Boolean,
        vararg active: PhysiologicalPatternReading,
    ): PhysiologicalPatternSnapshot =
        PhysiologicalPatternSnapshot(
            active = active.toList(),
            dominant = active.maxByOrNull { it.confidence }?.id,
            dominantConfidence = active.maxOfOrNull { it.confidence } ?: 0.0,
            suppressMealInterpretation = suppressMealInterpretation,
            suppressHyperRelease = false,
            suppressWaveletBoost = false,
            smbCapU = null,
            reasonSummary = active.joinToString(" + ") { it.id.name },
        )
}
