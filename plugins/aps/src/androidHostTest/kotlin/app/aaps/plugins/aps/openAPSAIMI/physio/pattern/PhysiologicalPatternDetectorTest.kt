package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PhysiologicalPatternDetectorTest {

    @BeforeEach
    fun resetHysteresis() {
        PhysiologicalPatternHysteresis.reset()
    }

    @Test
    fun morning_poor_sleep_detects_rise_pattern_with_caps() {
        val input = baseInput(
            hourOfDay = 7,
            deltaMgdlPer5 = 3.0,
            mealCobG = 0.0,
            sleepDebtMinutes = 90,
            physioContext = PhysioContextMTR(
                state = PhysioStateMTR.RECOVERY_NEEDED,
                confidence = 0.8,
                poorSleepDetected = true,
                hrvDepressed = true,
                hrvDeviationZ = -1.5,
            ),
        )
        val snap = PhysiologicalPatternDetector.detect(input)
        assertThat(snap.active.map { it.id }).contains(PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE)
        assertThat(snap.active.map { it.id }).contains(PhysiologicalPatternId.SLEEP_DEBT)
        assertThat(snap.suppressMealInterpretation).isTrue()
        assertThat(snap.suppressHyperRelease).isTrue()
        assertThat(snap.suppressWaveletBoost).isTrue()
        assertThat(snap.smbCapU!!).isWithin(0.01).of(0.50)
    }

    @Test
    fun confirmed_meal_wave_overrides_meal_suppression_from_hormonal_pattern() {
        val input = baseInput(
            hourOfDay = 7,
            deltaMgdlPer5 = 3.0,
            mealCobG = 0.0,
            sleepDebtMinutes = 90,
            physioContext = PhysioContextMTR(
                state = PhysioStateMTR.RECOVERY_NEEDED,
                confidence = 0.8,
                poorSleepDetected = true,
                hrvDepressed = true,
                hrvDeviationZ = -1.5,
            ),
            phaseOutput = PhysiologicalPhaseClassifier.Output(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.85,
                policy = BehavioralRiskPolicy.forPhase(
                    PhysiologicalPhase.DAWN_CORTISOL,
                    0.85,
                    "dawn",
                ),
            ),
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
        )
        val snap = PhysiologicalPatternDetector.detect(input)
        assertThat(snap.active.map { it.id }).contains(PhysiologicalPatternId.MEAL_FIRST_WAVE)
        assertThat(snap.suppressMealInterpretation).isFalse()
    }

    @Test
    fun iob_stacking_surveillance_caps_hyper_release() {
        val input = baseInput(
            stackingSurveillance = true,
            iobU = 9.5,
        )
        val snap = PhysiologicalPatternDetector.detect(input)
        assertThat(snap.active.map { it.id }).contains(PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE)
        assertThat(snap.suppressHyperRelease).isTrue()
        assertThat(snap.smbCapU!!).isWithin(0.01).of(0.50)
    }

    @Test
    fun exercise_acute_suppressed_during_meal_first_wave() {
        val input = baseInput(
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
            stepsLast15m = 1200,
            heartRateBpm = 92,
            restingHeartRateBpm = 58,
        )
        val snap = PhysiologicalPatternDetector.detect(input)
        assertThat(snap.active.map { it.id }).doesNotContain(PhysiologicalPatternId.EXERCISE_ACUTE)
        assertThat(snap.active.map { it.id }).contains(PhysiologicalPatternId.MEAL_FIRST_WAVE)
    }

    private fun baseInput(
        hourOfDay: Int = 12,
        deltaMgdlPer5: Double = 1.0,
        mealCobG: Double = 0.0,
        sleepDebtMinutes: Int = 0,
        physioContext: PhysioContextMTR? = null,
        phaseOutput: PhysiologicalPhaseClassifier.Output? = null,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        stackingSurveillance: Boolean = false,
        iobU: Double = 2.0,
        stepsLast15m: Int = 0,
        heartRateBpm: Int = 70,
        restingHeartRateBpm: Int = 60,
    ): PhysiologicalPatternInput = PhysiologicalPatternInput(
        bgMgdl = 180.0,
        targetBgMgdl = 100.0,
        highBgBandMgdl = 80.0,
        deltaMgdlPer5 = deltaMgdlPer5,
        shortAvgDeltaMgdlPer5 = deltaMgdlPer5,
        combinedDeltaMgdlPer5 = deltaMgdlPer5,
        mealCobG = mealCobG,
        hourOfDay = hourOfDay,
        stepsLast15m = stepsLast15m,
        heartRateBpm = heartRateBpm,
        restingHeartRateBpm = restingHeartRateBpm,
        iobU = iobU,
        maxIobU = 12.0,
        bestTerminalMgdl = 220.0,
        floorTerminalMgdl = 90.0,
        phaseOutput = phaseOutput,
        physioContext = physioContext,
        sleepDebtMinutes = sleepDebtMinutes,
        sleepEfficiency = 0.75,
        mealAbsorptionPhase = mealAbsorptionPhase,
        mealDeliveryPriority = mealAbsorptionPhase != MealAbsorptionPhase.NONE,
        stackingSurveillance = stackingSurveillance,
        endogenousCounterRegulatory = false,
        postHypoOrdinal = null,
        exerciseLockout = false,
        sportTime = false,
        sleepTime = false,
        contextIllness = false,
        contextStress = false,
        contextActivity = false,
        compressionImpossibleRise = false,
        dwellAboveHighBgMinutes = 0,
        trajectoryRelevanceScore = 0.5,
        nowMs = 1_700_000_000_000L,
    )
}
