package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InsulinLoadGovernorTest {

    @Test
    fun physiological_budget_scales_with_tdd_and_weight() {
        val fromTdd = InsulinLoadGovernor.physiologicalBudgetU(tdd24hU = 55.0, patientWeightKg = 50.0)
        val fromWeight = InsulinLoadGovernor.physiologicalBudgetU(tdd24hU = 30.0, patientWeightKg = 75.0)
        assertEquals(8.0, fromTdd, 0.01)
        assertEquals(8.0, fromWeight, 0.01)
    }

    @Test
    fun iob_budget_brake_leaves_non_boost_multipliers_untouched() {
        // Defensive (<=1.0) multipliers are already conservative — never relaxed.
        assertEquals(0.88, InsulinLoadGovernor.iobBudgetBrakedMultiplier(0.88, iobU = 8.0, budgetU = 9.8), 0.0001)
        assertEquals(1.0, InsulinLoadGovernor.iobBudgetBrakedMultiplier(1.0, iobU = 0.0, budgetU = 9.8), 0.0001)
    }

    @Test
    fun iob_budget_brake_fades_boost_as_iob_fills_budget() {
        val mult = 2.32
        // Zero IOB → full boost preserved (BG genuinely high, no load).
        assertEquals(2.32, InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = 0.0, budgetU = 9.8), 0.0001)
        // Midday field case: IOB 7.1 / budget 9.8 → headroom 0.2755 → ~1.36x (was 2.32x).
        assertEquals(1.364, InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = 7.1, budgetU = 9.8), 0.005)
        // At budget → boost fully neutralised to 1.0x.
        assertEquals(1.0, InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = 9.8, budgetU = 9.8), 0.0001)
        // Above budget → clamped to 1.0x (never below profile basal).
        assertEquals(1.0, InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = 12.0, budgetU = 9.8), 0.0001)
        // Negative IOB → headroom clamped to 1 → full boost.
        assertEquals(2.32, InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = -1.0, budgetU = 9.8), 0.0001)
    }

    @Test
    fun iob_budget_brake_is_monotonic_in_iob() {
        val mult = 2.0
        val low = InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = 2.0, budgetU = 10.0)
        val mid = InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = 5.0, budgetU = 10.0)
        val high = InsulinLoadGovernor.iobBudgetBrakedMultiplier(mult, iobU = 8.0, budgetU = 10.0)
        assertTrue(low > mid && mid > high) { "brake must decrease the boost as IOB rises: $low > $mid > $high" }
        assertTrue(high in 1.0..mult)
    }

    @Test
    fun full_tier_on_fast_rise_with_low_iob() {
        val e = InsulinLoadGovernor.evaluate(
            InsulinLoadGovernor.Input(
                iobU = 4.0,
                tdd24hU = 55.0,
                patientWeightKg = 75.0,
                deltaMgdlPer5 = 4.8,
                shortAvgDeltaMgdlPer5 = 4.2,
                deltaPrevMgdlPer5 = 4.0,
                bgDerivShort = 2.0,
                bgMgdl = 180.0,
                targetBgMgdl = 100.0,
                bestTerminalMgdl = 260.0,
                minPredictedBgMgdl = 220.0,
                eventualBgMgdl = 240.0,
                trajectoryEnergy = 2.0,
                trajectoryCoherence = 0.4,
                insulinActivityStageOrdinal = null,
                insulinActivityNow = 0.1,
                mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                mealDeliveryPriority = true,
            ),
        )
        assertEquals(InsulinLoadGovernor.Tier.FULL, e.tier)
        assertTrue(e.multiplierG >= 0.90)
    }

    @Test
    fun dampens_high_iob_meal_deceleration_and_trajectory_energy() {
        val e = InsulinLoadGovernor.evaluate(
            InsulinLoadGovernor.Input(
                iobU = 17.5,
                tdd24hU = 55.0,
                patientWeightKg = 75.0,
                deltaMgdlPer5 = 2.1,
                shortAvgDeltaMgdlPer5 = 2.8,
                deltaPrevMgdlPer5 = 3.4,
                bgDerivShort = -1.2,
                bgMgdl = 210.0,
                targetBgMgdl = 100.0,
                bestTerminalMgdl = 280.0,
                minPredictedBgMgdl = 165.0,
                eventualBgMgdl = 170.0,
                trajectoryEnergy = 8.5,
                trajectoryCoherence = 0.15,
                insulinActivityStageOrdinal = 1,
                insulinActivityNow = 0.45,
                mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                mealDeliveryPriority = true,
            ),
        )
        assertTrue(e.multiplierG < 0.85, "expected dampening, got g=${e.multiplierG}")
        assertTrue(e.stackScore > 0.45)
        assertTrue(e.deltaDecelScore > 0.2)
        assertTrue(e.smbTickCapU < 2.0)
    }

    @Test
    fun sharp_rise_escape_limits_dampening_despite_high_iob() {
        val e = InsulinLoadGovernor.evaluate(
            InsulinLoadGovernor.Input(
                iobU = 14.0,
                tdd24hU = 55.0,
                patientWeightKg = 75.0,
                deltaMgdlPer5 = 5.2,
                shortAvgDeltaMgdlPer5 = 4.6,
                deltaPrevMgdlPer5 = 4.8,
                bgDerivShort = 1.5,
                bgMgdl = 240.0,
                targetBgMgdl = 100.0,
                bestTerminalMgdl = 320.0,
                minPredictedBgMgdl = 280.0,
                eventualBgMgdl = 300.0,
                trajectoryEnergy = 6.0,
                trajectoryCoherence = 0.2,
                insulinActivityStageOrdinal = 1,
                insulinActivityNow = 0.5,
                mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                mealDeliveryPriority = true,
            ),
        )
        assertTrue(e.multiplierG >= 0.85, "escape should preserve correction, g=${e.multiplierG}")
        assertTrue(e.reasonCodes.contains("SHARP_RISE"))
    }

    @Test
    fun second_wave_reacceleration_restores_rise_score() {
        val decel = InsulinLoadGovernor.evaluate(
            baseMealInput(
                delta = 1.8,
                deltaPrev = 3.0,
                iob = 16.0,
                phase = MealAbsorptionPhase.INTER_WAVE,
            ),
        )
        val reaccel = InsulinLoadGovernor.evaluate(
            baseMealInput(
                delta = 2.4,
                deltaPrev = 1.8,
                iob = 16.5,
                phase = MealAbsorptionPhase.SECOND_WAVE,
                lastG = decel.multiplierG,
            ),
        )
        assertTrue(reaccel.riseScore > decel.riseScore)
        assertTrue(reaccel.multiplierG >= decel.multiplierG * 0.95)
    }

    @Test
    fun smoothing_reduces_tick_to_tick_oscillation() {
        val rawLow = InsulinLoadGovernor.evaluate(
            baseMealInput(delta = 2.0, deltaPrev = 3.2, iob = 15.0, lastG = 1.0),
        )
        assertTrue(rawLow.multiplierG > rawLow.rawMultiplierG * 0.9)
    }

    private fun baseMealInput(
        delta: Double,
        deltaPrev: Double,
        iob: Double,
        phase: MealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
        lastG: Double = 1.0,
    ): InsulinLoadGovernor.Input =
        InsulinLoadGovernor.Input(
            iobU = iob,
            tdd24hU = 55.0,
            patientWeightKg = 75.0,
            deltaMgdlPer5 = delta,
            shortAvgDeltaMgdlPer5 = delta * 0.95,
            deltaPrevMgdlPer5 = deltaPrev,
            bgDerivShort = (delta - deltaPrev) / 2.0,
            bgMgdl = 200.0,
            targetBgMgdl = 100.0,
            bestTerminalMgdl = 270.0,
            minPredictedBgMgdl = 190.0,
            eventualBgMgdl = 210.0,
            trajectoryEnergy = 7.0,
            trajectoryCoherence = 0.25,
            insulinActivityStageOrdinal = 1,
            insulinActivityNow = 0.35,
            mealAbsorptionPhase = phase,
            mealDeliveryPriority = true,
            lastMultiplierG = lastG,
        )
}
