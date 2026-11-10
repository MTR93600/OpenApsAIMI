package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.data.model.SourceSensor
import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternReading
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhysioLatentStateBuilderTest {

    // Gate thresholds this feeds: RBT SENSOR_BLOCK_THRESHOLD = 0.45, Harmonia SENSOR_UNCERTAIN < 0.40.
    private val block = 0.45

    @Test
    fun sensorConfidence_legacy_collapses_on_null_source_no_wearable() {
        // Legacy (wearable-weighted): null CGM source + low health-context snapshot → below the 0.45
        // block → SENSOR_LOW forces authority NONE all day. This is the reported failure.
        val legacy = PhysioLatentStateBuilder.buildSensorConfidence(
            snapshotConfidence = 0.31, sourceSensor = null, cgmFirst = false,
        )
        assertThat(legacy).isLessThan(block)
    }

    @Test
    fun sensorConfidence_cgmFirst_clears_block_on_null_source() {
        // CGM-first: a null/unknown source stays cautious but clears the 0.45 block → SOFT-eligible
        // (not fully blocked) even with no wearable data.
        val cgmFirst = PhysioLatentStateBuilder.buildSensorConfidence(
            snapshotConfidence = 0.31, sourceSensor = null, cgmFirst = true,
        )
        assertThat(cgmFirst).isAtLeast(block)
        // And it is strictly higher than legacy for the same inputs (decoupled from wearable).
        val legacy = PhysioLatentStateBuilder.buildSensorConfidence(0.31, null, false)
        assertThat(cgmFirst).isGreaterThan(legacy)
    }

    @Test
    fun sensorConfidence_cgmFirst_barely_moves_with_wearable_availability() {
        // Health-context is only a minor (20%) modifier now: swinging it 0→1 must not flip the gate
        // for a null source (stays SOFT-band, never the old 0.32→collapse).
        val noWear = PhysioLatentStateBuilder.buildSensorConfidence(0.0, null, cgmFirst = true)
        val fullWear = PhysioLatentStateBuilder.buildSensorConfidence(1.0, null, cgmFirst = true)
        assertThat(noWear).isAtLeast(0.40) // never collapses below Harmonia's SENSOR_UNCERTAIN
        assertThat(fullWear - noWear).isLessThan(0.25) // wearable is a minor modifier, not the driver
    }

    @Test
    fun build_caps_meal_probability_when_false_meal_suppression_is_active() {
        val hypothesisState = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.84,
                reason = "dawn",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.88,
                reason = "fast rise",
                deltaMgdlPer5 = 4.1,
                gapMgdl = 46.0,
                bestTerminalMgdl = 210.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.84,
                kineticScore = 0.79,
                trajectoryScore = 0.75,
                physioScore = 0.20,
            ),
            patternSnapshot = patternSnapshot(
                suppressMealInterpretation = true,
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.DAWN_CORTISOL,
                    confidence = 0.86,
                    reason = "dawn guard",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.MEAL_FIRST_WAVE,
                    confidence = 0.82,
                    reason = "meal first wave",
                ),
            ),
            correctionAggressionDecision = null,
            uamConfidence = 0.60,
        )
        val latent = PhysioLatentStateBuilder.build(
            snapshot = HealthContextSnapshot(
                sleepDebtMinutes = 75,
                confidence = 0.82,
                isValid = true,
            ),
            sourceSensor = SourceSensor.DEXCOM_G6_NATIVE,
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.84,
                reason = "dawn",
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.88,
                reason = "fast rise",
                deltaMgdlPer5 = 4.1,
                gapMgdl = 46.0,
                bestTerminalMgdl = 210.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.84,
                kineticScore = 0.79,
                trajectoryScore = 0.75,
                physioScore = 0.20,
            ),
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot(
                suppressMealInterpretation = true,
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.DAWN_CORTISOL,
                    confidence = 0.86,
                    reason = "dawn guard",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.MEAL_FIRST_WAVE,
                    confidence = 0.82,
                    reason = "meal first wave",
                ),
            ),
            physioContext = PhysioContextMTR(
                state = PhysioStateMTR.RECOVERY_NEEDED,
                confidence = 0.70,
                poorSleepDetected = true,
            ),
            physioTrace = PhysioDecisionTraceMTR(
                inflammationLatentIndex = 0.40,
                inflammationConfidence = 0.65,
            ),
            correctionAggressionDecision = null,
            chronicInflammation = null,
            autonomicStress = 0.12,
            inflammationRecovery = 0.44,
            hormonalCircadian = 0.90,
        )

        assertThat(latent.falseMealSuppression).isTrue()
        assertThat(latent.mealProb).isAtMost(0.35)
        assertThat(latent.endogenousGlucoseDrive).isGreaterThan(0.80)
        assertThat(latent.circadianSiFactor).isLessThan(1.0)
    }

    @Test
    fun build_populates_resistance_and_sensor_confidence_from_existing_axes() {
        val reboundDecision = CorrectionAggressionGate.Decision(
            tier = CorrectionAggressionGate.Tier.REBOUND_GUARD,
            mealTierFull = false,
            allowGlobalHyperKicker = false,
            allowRocketBasalScale = false,
            allowRocketHypoOverride = false,
            maxBasalScaleCap = 1.5,
            reasonTag = "post_hypo_rebound_guard",
        )
        val hypothesisState = UamHypothesisStateBuilder.build(
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.STRESS_CORTISOL,
                confidence = 0.80,
                reason = "stress",
            ),
            mealAbsorptionOutput = null,
            patternSnapshot = patternSnapshot(
                suppressMealInterpretation = false,
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.POST_HYPO_REBOUND,
                    confidence = 0.76,
                    reason = "rebound",
                ),
            ),
            correctionAggressionDecision = reboundDecision,
            uamConfidence = 0.40,
        )
        val latent = PhysioLatentStateBuilder.build(
            snapshot = HealthContextSnapshot(
                sleepDebtMinutes = 95,
                confidence = 0.90,
                isValid = true,
            ),
            sourceSensor = SourceSensor.DEXCOM_G7_NATIVE,
            phaseOutput = phaseOutput(
                phase = PhysiologicalPhase.STRESS_CORTISOL,
                confidence = 0.80,
                reason = "stress",
            ),
            mealAbsorptionOutput = null,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot(
                suppressMealInterpretation = false,
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.POST_HYPO_REBOUND,
                    confidence = 0.76,
                    reason = "rebound",
                ),
            ),
            physioContext = PhysioContextMTR(
                state = PhysioStateMTR.INFECTION_RISK,
                confidence = 0.82,
                poorSleepDetected = true,
                hrvDepressed = true,
                rhrElevated = true,
            ),
            physioTrace = PhysioDecisionTraceMTR(
                inflammationLatentIndex = 0.78,
                inflammationConfidence = 0.88,
            ),
            correctionAggressionDecision = reboundDecision,
            chronicInflammation = InflammationAdjuster.InflammationResult(
                basalMultiplier = 1.05,
                smbMultiplier = 0.92,
                reason = "Verneuil:FLARE",
            ),
            autonomicStress = 0.78,
            inflammationRecovery = 0.92,
            hormonalCircadian = 0.35,
        )

        assertThat(latent.transientResistanceProb).isGreaterThan(0.90)
        assertThat(latent.postHypoReboundProb).isGreaterThan(0.85)
        assertThat(latent.sleepDebtScore).isGreaterThan(0.70)
        assertThat(latent.sensorConfidence).isWithin(1e-9).of(0.855)
        assertThat(latent.toAttentionMask().toList()).containsExactly(0.78, 0.92, 0.35).inOrder()
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
