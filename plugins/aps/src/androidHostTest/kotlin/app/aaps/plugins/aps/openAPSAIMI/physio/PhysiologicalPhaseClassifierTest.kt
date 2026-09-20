package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhysiologicalPhaseClassifierTest {

    private fun dawnInput(
        bg: Double = 122.0,
        delta: Double = 2.0,
        bestT: Double = 145.0,
        steps: Int = 800,
    ) = PhysiologicalPhaseClassifier.Input(
        bgMgdl = bg,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 140.0,
        deltaMgdlPer5 = delta,
        shortAvgDeltaMgdlPer5 = 1.8,
        combinedDeltaMgdlPer5 = 1.9,
        mealCobG = 0.0,
        hourOfDay = 7,
        stepsLast15m = steps,
        heartRateBpm = 78,
        restingHeartRateBpm = 62,
        bestTerminalMgdl = bestT,
        floorTerminalMgdl = 95.0,
        dwellAboveHighBgMinutes = 5,
        wCycleEnabled = false,
        wCycleTrackingMode = null,
        wCyclePhase = null,
    )

    /**
     * Ticks with the preferences of the reported field case: target 90, high 140, so high band 50
     * and the level test of the cortisol escape sits at BG 170 (90 + 1.6 * 50).
     */
    private fun cortisolEscapeInput(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        bestT: Double,
        floorT: Double,
        hour: Int,
        heartRate: Int,
        restingHeartRate: Int,
        cob: Double = 0.0,
    ) = dawnInput(bg = bg, delta = delta, bestT = bestT, steps = 60).copy(
        targetBgMgdl = 90.0,
        shortAvgDeltaMgdlPer5 = shortAvgDelta,
        combinedDeltaMgdlPer5 = delta,
        mealCobG = cob,
        hourOfDay = hour,
        heartRateBpm = heartRate,
        restingHeartRateBpm = restingHeartRate,
        floorTerminalMgdl = floorT,
    )

    @Test
    fun morning_near_target_male_circadian() {
        val out = PhysiologicalPhaseClassifier.classify(dawnInput())
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, out.phase)
        assertTrue(out.policy.extendedDawnGuard)
        assertEquals(HyperSeverityTier.OFF, out.policy.maxHtrTier)
    }

    @Test
    fun fast_morning_rise_prefers_stress_cortisol_over_meal() {
        val out = PhysiologicalPhaseClassifier.classify(
            dawnInput(delta = 4.0, bestT = 220.0, steps = 50).copy(
                heartRateBpm = 85,
                restingHeartRateBpm = 62,
            ),
        )
        assertEquals(PhysiologicalPhase.STRESS_CORTISOL, out.phase)
        assertEquals(HyperSeverityTier.EMERGING, out.policy.maxHtrTier)
    }

    @Test
    fun meal_like_rise_overrides_hormonal_at_lunch() {
        val out = PhysiologicalPhaseClassifier.classify(
            dawnInput(delta = 4.0, bestT = 220.0, steps = 50).copy(hourOfDay = 12),
        )
        assertEquals(PhysiologicalPhase.MEAL_UNDECLARED, out.phase)
    }

    /** Field replay 04:16 — BG ~109, inflated UAM bestT, dev still near target. */
    @Test
    fun dawn_near_target_uam_ramp_not_meal() {
        val out = PhysiologicalPhaseClassifier.classify(
            dawnInput(
                bg = 109.0,
                delta = 3.8,
                bestT = 242.0,
            ).copy(
                hourOfDay = 4,
                shortAvgDeltaMgdlPer5 = 3.2,
                combinedDeltaMgdlPer5 = 3.4,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, out.phase)
        assertTrue(out.policy.extendedDawnGuard)
    }

    @Test
    fun meal_dominant_blocks_stress_during_lunch_ramp() {
        val out = PhysiologicalPhaseClassifier.classify(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 167.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 4.2,
                shortAvgDeltaMgdlPer5 = 3.8,
                combinedDeltaMgdlPer5 = 4.0,
                mealCobG = 0.0,
                hourOfDay = 11,
                stepsLast15m = 200,
                heartRateBpm = 105,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 201.0,
                floorTerminalMgdl = 120.0,
                dwellAboveHighBgMinutes = 10,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertEquals(PhysiologicalPhase.MEAL_UNDECLARED, out.phase)
    }

    @Test
    fun htr_off_under_hormonal_policy() {
        val phase = PhysiologicalPhaseClassifier.classify(dawnInput())
        val htr = app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseEvaluator.evaluate(
            app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = 122.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 2.0,
                shortAvgDeltaMgdlPer5 = 1.8,
                combinedDeltaMgdlPer5 = 1.9,
                floorTerminalMgdl = 95.0,
                bestTerminalMgdl = 145.0,
                tdd24hU = 55.0,
                iobU = 2.0,
                maxIobU = 20.0,
                maxSmbEffectiveU = 5.0,
                v3SmbU = 1.9,
                dwellAboveHighBgMinutes = 5,
                trajectoryType = null,
                minPredictedBgMgdl = 110.0,
                aggressive = true,
                behavioralRisk = phase.policy,
            ),
        )
        assertTrue(!htr.active)
        assertTrue(htr.reason.contains("physio"))
    }

    /** Field replay 05/06 ~07:31 — UAM bestT inflated, COB=0; dawn hormonal near target, not meal. */
    @Test
    fun endogenous_cortisol_ramp_not_meal_undeclared() {
        val out = PhysiologicalPhaseClassifier.classify(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 119.0,
                targetBgMgdl = 115.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 3.0,
                shortAvgDeltaMgdlPer5 = 2.5,
                combinedDeltaMgdlPer5 = 2.8,
                mealCobG = 0.0,
                hourOfDay = 7,
                stepsLast15m = 188,
                heartRateBpm = 57,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 218.0,
                floorTerminalMgdl = 100.0,
                dwellAboveHighBgMinutes = 5,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, out.phase)
        assertEquals(HyperSeverityTier.OFF, out.policy.maxHtrTier)
        assertTrue(out.policy.suppressMealLikeScenario)
        val mealFlip = PhysiologicalPhaseClassifier.classify(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 127.0,
                targetBgMgdl = 115.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 3.0,
                shortAvgDeltaMgdlPer5 = 2.5,
                combinedDeltaMgdlPer5 = 2.8,
                mealCobG = 0.0,
                hourOfDay = 7,
                stepsLast15m = 188,
                heartRateBpm = 57,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 218.0,
                floorTerminalMgdl = 100.0,
                dwellAboveHighBgMinutes = 5,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertEquals(PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY, mealFlip.phase)
    }

    @Test
    fun endogenous_hysteresis_blocks_meal_flip() {
        EndogenousPhaseHysteresis.reset()
        val dawn = PhysiologicalPhaseClassifier.classifyWithHysteresis(
            PhysiologicalPhaseClassifier.Input(
                bgMgdl = 119.0,
                targetBgMgdl = 115.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 3.0,
                shortAvgDeltaMgdlPer5 = 2.5,
                combinedDeltaMgdlPer5 = 2.8,
                mealCobG = 0.0,
                hourOfDay = 7,
                stepsLast15m = 50,
                heartRateBpm = 57,
                restingHeartRateBpm = 62,
                bestTerminalMgdl = 218.0,
                floorTerminalMgdl = 100.0,
                dwellAboveHighBgMinutes = 5,
                wCycleEnabled = false,
                wCycleTrackingMode = null,
                wCyclePhase = null,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, dawn.phase)
        val flipped = PhysiologicalPhaseClassifier.classify(
            dawnInput(bg = 126.0, delta = 1.0, bestT = 64.0).copy(
                floorTerminalMgdl = 60.0,
            ),
        )
        assertEquals(PhysiologicalPhase.OFF, flipped.phase)
        val held = PhysiologicalPhaseClassifier.classifyWithHysteresis(
            dawnInput(bg = 126.0, delta = 1.0, bestT = 64.0).copy(
                floorTerminalMgdl = 60.0,
            ),
        )
        assertEquals(PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL, held.phase)
        EndogenousPhaseHysteresis.reset()
    }

    /**
     * Field replay 13 Aug 08:16 — breakfast start read as cortisol; the 0.75 U SMB floor cap was
     * the binding stage on 12 of 15 ticks while BG rose 140 mg/dL.
     */
    @Test
    fun steep_breakfast_rise_is_not_stress_cortisol_and_keeps_no_smb_floor_cap() {
        val out = PhysiologicalPhaseClassifier.classify(
            cortisolEscapeInput(
                bg = 143.0,
                delta = 17.5,
                shortAvgDelta = 16.5,
                bestT = 260.0,
                floorT = 150.0,
                hour = 8,
                heartRate = 96,
                restingHeartRate = 49,
            ),
        )
        assertNotEquals(PhysiologicalPhase.STRESS_CORTISOL, out.phase)
        assertEquals(PhysiologicalPhase.MEAL_UNDECLARED, out.phase)
        assertTrue(out.policy.smbFloorCapU.isInfinite())
    }

    /** Field replay 12 Aug 09:31 — slower rise but already BG 178, so the level clause must fire. */
    @Test
    fun sustained_rise_high_above_target_is_not_stress_cortisol() {
        val out = PhysiologicalPhaseClassifier.classify(
            cortisolEscapeInput(
                bg = 178.0,
                delta = 6.4,
                shortAvgDelta = 7.0,
                bestT = 250.0,
                floorT = 160.0,
                hour = 9,
                heartRate = 96,
                restingHeartRate = 49,
            ),
        )
        assertNotEquals(PhysiologicalPhase.STRESS_CORTISOL, out.phase)
        assertEquals(PhysiologicalPhase.MEAL_UNDECLARED, out.phase)
    }

    /** Regression guard: the fastest genuine cortisol ramp in the corpus (9.1 mg/dL per 5 min). */
    @Test
    fun genuine_dawn_cortisol_ramp_stays_stress_cortisol() {
        val out = PhysiologicalPhaseClassifier.classify(
            cortisolEscapeInput(
                bg = 134.0,
                delta = 9.1,
                shortAvgDelta = 8.0,
                bestT = 200.0,
                floorT = 120.0,
                hour = 6,
                heartRate = 88,
                restingHeartRate = 52,
            ),
        )
        assertEquals(PhysiologicalPhase.STRESS_CORTISOL, out.phase)
        assertEquals(0.75, out.policy.smbFloorCapU, 0.001)
    }

    @Test
    fun mild_morning_ramp_stays_stress_cortisol() {
        val out = PhysiologicalPhaseClassifier.classify(
            cortisolEscapeInput(
                bg = 118.0,
                delta = 4.7,
                shortAvgDelta = 4.4,
                bestT = 170.0,
                floorT = 110.0,
                hour = 7,
                heartRate = 84,
                restingHeartRate = 54,
            ),
        )
        assertEquals(PhysiologicalPhase.STRESS_CORTISOL, out.phase)
        assertEquals(0.75, out.policy.smbFloorCapU, 0.001)
    }

    @Test
    fun cortisol_escape_does_not_fire_when_carbs_are_declared() {
        val input = cortisolEscapeInput(
            bg = 150.0,
            delta = 20.0,
            shortAvgDelta = 18.0,
            bestT = 260.0,
            floorT = 150.0,
            hour = 8,
            heartRate = 96,
            restingHeartRate = 49,
            cob = 2.0,
        )
        assertFalse(
            PhysiologicalPhaseClassifier.isTooSteepForCortisolAlone(
                input = input,
                highBand = 50.0,
                dev = input.bgMgdl - input.targetBgMgdl,
            ),
        )
    }

    @Test
    fun cortisol_escape_does_not_fire_just_below_the_rate_threshold() {
        val input = cortisolEscapeInput(
            bg = 143.0,
            delta = 10.9,
            shortAvgDelta = 10.0,
            bestT = 250.0,
            floorT = 150.0,
            hour = 8,
            heartRate = 96,
            restingHeartRate = 49,
        )
        assertFalse(
            PhysiologicalPhaseClassifier.isTooSteepForCortisolAlone(
                input = input,
                highBand = 50.0,
                dev = input.bgMgdl - input.targetBgMgdl,
            ),
        )
        assertEquals(
            PhysiologicalPhase.STRESS_CORTISOL,
            PhysiologicalPhaseClassifier.classify(input).phase,
        )
    }

    @Test
    fun cortisol_escape_does_not_fire_just_below_the_sustained_threshold() {
        val input = cortisolEscapeInput(
            bg = 175.0,
            delta = 5.2,
            shortAvgDelta = 4.9,
            bestT = 250.0,
            floorT = 160.0,
            hour = 9,
            heartRate = 96,
            restingHeartRate = 49,
        )
        assertFalse(
            PhysiologicalPhaseClassifier.isTooSteepForCortisolAlone(
                input = input,
                highBand = 50.0,
                dev = input.bgMgdl - input.targetBgMgdl,
            ),
        )
        assertEquals(
            PhysiologicalPhase.STRESS_CORTISOL,
            PhysiologicalPhaseClassifier.classify(input).phase,
        )
    }

    /**
     * Guards the second STRESS_CORTISOL site, which runs after the meal checks: when no meal branch
     * fires, the steep tick must not fall back into the cortisol phase and its floor cap.
     */
    @Test
    fun steep_rise_outside_the_morning_window_does_not_fall_back_to_stress_cortisol() {
        val input = cortisolEscapeInput(
            bg = 150.0,
            delta = 12.0,
            shortAvgDelta = 11.0,
            bestT = 155.0,
            floorT = 140.0,
            hour = 13,
            heartRate = 96,
            restingHeartRate = 49,
        )
        val out = PhysiologicalPhaseClassifier.classify(input)
        assertNotEquals(PhysiologicalPhase.STRESS_CORTISOL, out.phase)
        assertEquals(PhysiologicalPhase.OFF, out.phase)
        assertTrue(out.policy.smbFloorCapU.isInfinite())
    }

    /**
     * The hold of `EndogenousPhaseHysteresis` must not give the cortisol policy back to the first
     * ticks of a meal. Without the escape mark it held for `HOLD_TICKS_DEFAULT` ticks, which on the
     * reported morning covered 08:11 to 08:26 — the whole start of the rise.
     */
    @Test
    fun steep_rise_clears_the_endogenous_hold_instead_of_being_damped() {
        EndogenousPhaseHysteresis.reset()
        val cortisolTick = PhysiologicalPhaseClassifier.classify(
            cortisolEscapeInput(
                bg = 134.0,
                delta = 9.1,
                shortAvgDelta = 8.0,
                bestT = 175.0,
                floorT = 125.0,
                hour = 6,
                heartRate = 88,
                restingHeartRate = 52,
            ),
        )
        assertEquals(PhysiologicalPhase.STRESS_CORTISOL, EndogenousPhaseHysteresis.stabilize(cortisolTick).phase)

        val steepTick = PhysiologicalPhaseClassifier.classify(
            cortisolEscapeInput(
                bg = 143.0,
                delta = 17.5,
                shortAvgDelta = 16.5,
                bestT = 260.0,
                floorT = 150.0,
                hour = 8,
                heartRate = 96,
                restingHeartRate = 49,
            ),
        )
        assertTrue(steepTick.breaksEndogenousHold)
        val held = EndogenousPhaseHysteresis.stabilize(steepTick)
        assertNotEquals(PhysiologicalPhase.STRESS_CORTISOL, held.phase)
        assertTrue(held.policy.smbFloorCapU.isInfinite())
        EndogenousPhaseHysteresis.reset()
    }

    /** A genuine cortisol tick still arms the hold, so ordinary flip-flop is still damped. */
    @Test
    fun cortisol_tick_still_arms_the_hold_for_an_ordinary_flip_flop() {
        EndogenousPhaseHysteresis.reset()
        val cortisolTick = PhysiologicalPhaseClassifier.classify(
            cortisolEscapeInput(
                bg = 118.0,
                delta = 4.7,
                shortAvgDelta = 4.4,
                bestT = 150.0,
                floorT = 110.0,
                hour = 6,
                heartRate = 88,
                restingHeartRate = 52,
            ),
        )
        assertEquals(PhysiologicalPhase.STRESS_CORTISOL, cortisolTick.phase)
        EndogenousPhaseHysteresis.stabilize(cortisolTick)

        val mildTick = PhysiologicalPhaseClassifier.classify(
            dawnInput(delta = 4.0, bestT = 220.0, steps = 50).copy(hourOfDay = 12),
        )
        assertEquals(PhysiologicalPhase.MEAL_UNDECLARED, mildTick.phase)
        assertFalse(mildTick.breaksEndogenousHold)
        assertEquals(PhysiologicalPhase.STRESS_CORTISOL, EndogenousPhaseHysteresis.stabilize(mildTick).phase)
        EndogenousPhaseHysteresis.reset()
    }

    @Test
    fun scenario_cap_limits_best_terminal() {
        val policy = BehavioralRiskPolicy.forPhase(
            PhysiologicalPhase.DAWN_CORTISOL,
            0.9,
            "test",
        )
        val capped = HormonalScenarioTerminalCap.capBestTerminalMgdl(120.0, 280.0, policy)
        assertEquals(170.0, capped, 0.01)
    }
}
