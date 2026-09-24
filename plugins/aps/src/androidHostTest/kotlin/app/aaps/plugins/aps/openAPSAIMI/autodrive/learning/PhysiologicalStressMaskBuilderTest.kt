package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioDecisionTraceMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternReading
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhysiologicalStressMaskBuilderTest {

    @Test
    fun build_suppresses_pure_exercise_autonomic_signal() {
        val mask = PhysiologicalStressMaskBuilder.build(
            snapshot = HealthContextSnapshot(
                stepsLast15m = 1400,
                hrNow = 138,
                rhrResting = 58,
                hrvRmssd = 34.0,
                isValid = true,
                confidence = 0.95,
            ),
            physioContext = PhysioContextMTR.NEUTRAL,
            physioTrace = null,
            phaseOutput = null,
            patternSnapshot = patternSnapshot(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.EXERCISE_ACUTE,
                    confidence = 0.90,
                    reason = "sport walk",
                ),
            ),
            correctionAggressionDecision = null,
            chronicInflammation = null,
        )

        assertThat(mask.autonomicStress).isAtMost(0.18)
        assertThat(mask.inflammationRecovery).isEqualTo(0.0)
        assertThat(mask.hormonalCircadian).isEqualTo(0.0)
    }

    @Test
    fun build_keeps_explicit_stress_signal_even_when_steps_are_high() {
        val mask = PhysiologicalStressMaskBuilder.build(
            snapshot = HealthContextSnapshot(
                stepsLast15m = 900,
                hrNow = 126,
                rhrResting = 60,
                hrvRmssd = 18.0,
                isValid = true,
                confidence = 0.92,
            ),
            physioContext = PhysioContextMTR(
                state = PhysioStateMTR.STRESS_DETECTED,
                confidence = 0.82,
                hrvDepressed = true,
                rhrElevated = true,
            ),
            physioTrace = null,
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.STRESS_CORTISOL,
                confidence = 0.84,
                reason = "stress rise",
            ),
            patternSnapshot = patternSnapshot(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.EXERCISE_ACUTE,
                    confidence = 0.85,
                    reason = "walk",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.STRESS_CORTISOL_ACUTE,
                    confidence = 0.79,
                    reason = "cortisol",
                ),
            ),
            correctionAggressionDecision = null,
            chronicInflammation = null,
        )

        assertThat(mask.autonomicStress).isGreaterThan(0.75)
    }

    @Test
    fun build_raises_hormonal_axis_from_existing_dawn_guard() {
        val mask = PhysiologicalStressMaskBuilder.build(
            snapshot = HealthContextSnapshot.EMPTY,
            physioContext = PhysioContextMTR.NEUTRAL,
            physioTrace = null,
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.84,
                reason = "dawn window",
            ),
            patternSnapshot = patternSnapshot(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.DAWN_CORTISOL,
                    confidence = 0.88,
                    reason = "dawn",
                ),
            ),
            correctionAggressionDecision = null,
            chronicInflammation = null,
        )

        assertThat(mask.hormonalCircadian).isGreaterThan(0.90)
        assertThat(mask.autonomicStress).isEqualTo(0.0)
    }

    @Test
    fun build_raises_inflammation_axis_from_recovery_and_rebound_signals() {
        val mask = PhysiologicalStressMaskBuilder.build(
            snapshot = HealthContextSnapshot(
                sleepDebtMinutes = 105,
                hrvRmssd = 20.0,
                isValid = true,
                confidence = 0.90,
            ),
            physioContext = PhysioContextMTR(
                state = PhysioStateMTR.RECOVERY_NEEDED,
                confidence = 0.76,
                poorSleepDetected = true,
                hrvDepressed = true,
            ),
            physioTrace = PhysioDecisionTraceMTR(
                inflammationLatentIndex = 0.72,
                inflammationConfidence = 0.81,
            ),
            phaseOutput = null,
            patternSnapshot = patternSnapshot(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.POST_HYPO_REBOUND,
                    confidence = 0.74,
                    reason = "recent hypo",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.SLEEP_DEBT,
                    confidence = 0.78,
                    reason = "sleep debt",
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
            chronicInflammation = InflammationAdjuster.InflammationResult(
                basalMultiplier = 1.05,
                smbMultiplier = 0.90,
                isfMultiplier = 1.0,
                reason = "Verneuil:FLARE",
            ),
        )

        assertThat(mask.inflammationRecovery).isGreaterThan(0.95)
        assertThat(mask.hormonalCircadian).isEqualTo(0.0)
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

    private fun patternSnapshot(vararg active: PhysiologicalPatternReading): PhysiologicalPatternSnapshot =
        PhysiologicalPatternSnapshot(
            active = active.toList(),
            dominant = active.maxByOrNull { it.confidence }?.id,
            dominantConfidence = active.maxOfOrNull { it.confidence } ?: 0.0,
            suppressMealInterpretation = false,
            suppressHyperRelease = false,
            suppressWaveletBoost = false,
            smbCapU = null,
            reasonSummary = active.joinToString(" + ") { it.id.name },
        )
}
