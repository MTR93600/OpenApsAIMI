package app.aaps.plugins.aps.openAPSAIMI.physio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MealAbsorptionPhaseEngineTest {

    @BeforeEach
    fun reset() {
        MealAbsorptionMemory.reset()
        MealAbsorptionPhaseHysteresis.reset()
        CircadianMealProfileStore.resetForTests()
    }

    private fun baseInput(
        bg: Double = 160.0,
        delta: Double = 3.0,
        shortAvg: Double = 2.5,
        bestT: Double = 220.0,
        floorT: Double = 100.0,
        hour: Int = 13,
        deltaPrev: Double? = null,
        gapPrev: Double? = null,
        nowMs: Long = 1_000_000L,
    ) = MealAbsorptionPhaseEngine.Input(
        bgMgdl = bg,
        targetBgMgdl = 115.0,
        highBgPreferenceMgdl = 140.0,
        deltaMgdlPer5 = delta,
        shortAvgDeltaMgdlPer5 = shortAvg,
        combinedDeltaMgdlPer5 = 3.0,
        deltaPrevMgdlPer5 = deltaPrev,
        mealCobG = 0.0,
        hourOfDay = hour,
        iobU = 8.0,
        maxIobU = 20.0,
        bestTerminalMgdl = bestT,
        floorTerminalMgdl = floorT,
        gapPrevMgdl = gapPrev,
        heartRateBpm = 78,
        restingHeartRateBpm = 62,
        stepsLast15m = 50,
        uamConfidence = 0.5,
        mealIntent = false,
        physiologicalPhase = PhysiologicalPhase.MEAL_UNDECLARED,
        nowMs = nowMs,
    )

    @Test
    fun lunch_window_first_wave_fast_rise() {
        val out = MealAbsorptionPhaseEngine.evaluate(
            baseInput(bg = 105.0, delta = 9.0, shortAvg = 7.5, bestT = 218.0),
        )
        assertEquals(MealAbsorptionPhase.FIRST_WAVE, out.phase)
        assertTrue(out.mealDeliveryPriority)
    }

    @Test
    fun peak_correction_blocks_surveillance_path() {
        MealAbsorptionMemory.update(
            MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.75,
                reason = "lunch seed",
                deltaMgdlPer5 = 10.0,
                gapMgdl = 175.0,
                bestTerminalMgdl = 278.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.85,
                kineticScore = 0.9,
                trajectoryScore = 0.8,
                physioScore = 0.1,
            ),
            nowMs = 990_000L,
        )
        val out = MealAbsorptionPhaseEngine.evaluate(
            baseInput(bg = 219.0, delta = -1.0, shortAvg = 0.1, bestT = 77.0, floorT = 45.0, nowMs = 1_000_000L),
        )
        assertEquals(MealAbsorptionPhase.PEAK_CORRECTION, out.phase)
        assertTrue(out.phase.bypassesIobSurveillance)
    }

    @Test
    fun second_wave_after_memory_reacceleration() {
        MealAbsorptionMemory.update(
            MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.INTER_WAVE,
                belief = 0.6,
                reason = "seed",
                deltaMgdlPer5 = 0.5,
                gapMgdl = 6.0,
                bestTerminalMgdl = 45.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.8,
                kineticScore = 0.2,
                trajectoryScore = 0.1,
                physioScore = 0.0,
            ),
            nowMs = 900_000L,
        )
        val out = MealAbsorptionPhaseEngine.evaluate(
            baseInput(
                bg = 234.0,
                delta = 2.5,
                shortAvg = 2.0,
                bestT = 76.0,
                floorT = 45.0,
                deltaPrev = 0.5,
                nowMs = 1_000_000L,
            ),
        )
        assertEquals(MealAbsorptionPhase.SECOND_WAVE, out.phase)
        assertTrue(out.mealDeliveryPriority)
    }

    @Test
    fun chrono_prior_high_at_lunch_without_cob() {
        val prior = MealAbsorptionPhaseEngine.chronoPrior(12)
        assertTrue(prior >= 0.8)
        val out = MealAbsorptionPhaseEngine.evaluate(
            baseInput(bg = 95.0, delta = 2.6, shortAvg = 2.3, bestT = 160.0, hour = 12),
        )
        assertTrue(out.belief >= 0.45)
    }

    @Test
    fun learned_breakfast_center_raises_late_morning_prior() {
        CircadianMealProfileStore.replaceProfileForTests(
            CircadianMealProfileSnapshot(
                breakfastCenterHour = 9.75,
                breakfastSamples = 12,
            ),
        )
        val prior = MealAbsorptionPhaseEngine.chronoPrior(10)
        assertTrue(prior > CircadianMealProfileStore.defaultPriorForHour(10))
        assertTrue(prior >= 0.55)
    }

    @Test
    fun learned_dawn_center_suppresses_false_meal_prior_early_morning() {
        CircadianMealProfileStore.replaceProfileForTests(
            CircadianMealProfileSnapshot(
                breakfastCenterHour = 9.50,
                breakfastSamples = 10,
                dawnCenterHour = 6.00,
                dawnSamples = 12,
            ),
        )
        val prior = MealAbsorptionPhaseEngine.chronoPrior(6)
        assertTrue(prior < CircadianMealProfileStore.defaultPriorForHour(6))
        assertTrue(prior <= 0.15)
    }

    @Test
    fun stacking_bypass_on_second_wave() {
        val eval = app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance.evaluate(
            bg = 234.0,
            delta = 2.5,
            shortAvgDelta = 2.0,
            targetBg = 115.0,
            iob = 14.0,
            maxIob = 20.0,
            eventualBg = 200.0,
            minPredBg = 180.0,
            trajectoryEnergy = 0.5,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = false,
            mealAbsorptionPhase = MealAbsorptionPhase.SECOND_WAVE,
        )
        assertEquals(app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance.Kind.CORRECTION_ACTIVE, eval.kind)
        assertEquals("meal_absorption_second_wave", eval.activeReason)
    }

    @Test
    fun stress_classifier_blocked_when_meal_memory_active() {
        MealAbsorptionMemory.update(
            MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.7,
                reason = "seed",
                deltaMgdlPer5 = 7.0,
                gapMgdl = 100.0,
                bestTerminalMgdl = 200.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.8,
                kineticScore = 0.9,
                trajectoryScore = 0.5,
                physioScore = 0.1,
            ),
            nowMs = System.currentTimeMillis(),
        )
        val stress = PhysiologicalPhaseClassifier.classify(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 183.0,
                targetBgMgdl = 115.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 9.0,
                shortAvgDeltaMgdlPer5 = 8.0,
                combinedDeltaMgdlPer5 = 9.0,
                mealCobG = 0.0,
                hourOfDay = 13,
                stepsLast15m = 50,
                heartRateBpm = 88,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 259.0,
                floorTerminalMgdl = 100.0,
                dwellAboveHighBgMinutes = 10,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertFalse(stress.phase == PhysiologicalPhase.STRESS_CORTISOL)
    }
}

