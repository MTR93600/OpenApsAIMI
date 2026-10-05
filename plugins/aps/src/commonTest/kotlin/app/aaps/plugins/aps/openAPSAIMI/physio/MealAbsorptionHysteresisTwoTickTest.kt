package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The process singleton keeps a meal-absorption hold from one tick to the next.
 * Same object on Android and on iOS. A fresh process has no hold. A FIRST_WAVE tick
 * followed by a flat NONE tick keeps the hold, and the shell prints that second tick
 * with the same line as [DetermineBasalAIMI2.refreshMealAbsorptionPhase].
 */
class MealAbsorptionHysteresisTwoTickTest {

    @AfterTest
    fun clearHold() {
        MealAbsorptionPhaseHysteresis.reset()
        MealAbsorptionMemory.reset()
    }

    @Test
    fun freshTickHasNoHoldAndTheNextFlatTickKeepsFirstWave() {
        clearHold()
        val first = MealAbsorptionPhaseEngine.evaluate(wave(nowMs = T0))
        assertEquals(MealAbsorptionPhase.FIRST_WAVE, first.phase)
        assertFalse(first.reason.contains("hysteresis hold"))
        assertEquals(
            "🍽️ MEAL_ABSORPTION: FIRST_WAVE B=1.00 pri=true waves=1 " +
                "(FIRST_WAVE B=1.00 π=0.85 K=1.00 T=0.00 P=0.35)",
            shellLine(first),
        )

        val second = MealAbsorptionPhaseEngine.evaluate(flat(nowMs = T0 + 5L * 60L * 1000L))
        assertEquals(MealAbsorptionPhase.FIRST_WAVE, second.phase)
        assertEquals("meal absorption hysteresis hold", second.reason)
        assertEquals(
            "🍽️ MEAL_ABSORPTION: FIRST_WAVE B=0.45 pri=false waves=1 (meal absorption hysteresis hold)",
            shellLine(second),
        )
    }

    private fun shellLine(output: MealAbsorptionPhaseEngine.Output): String =
        "🍽️ MEAL_ABSORPTION: ${output.phase.name} B=${aimiFmt2(output.belief)} " +
            "pri=${output.mealDeliveryPriority} waves=${output.waveCount} (${output.reason})"

    private fun wave(nowMs: Long) = MealAbsorptionPhaseEngine.Input(
        bgMgdl = 180.0,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 0.0,
        deltaMgdlPer5 = 6.0,
        shortAvgDeltaMgdlPer5 = 6.0,
        combinedDeltaMgdlPer5 = 6.0,
        deltaPrevMgdlPer5 = null,
        mealCobG = 20.0,
        hourOfDay = 12,
        iobU = 1.0,
        maxIobU = 10.0,
        bestTerminalMgdl = 180.0,
        floorTerminalMgdl = 180.0,
        gapPrevMgdl = null,
        heartRateBpm = 72,
        restingHeartRateBpm = 60,
        stepsLast15m = 0,
        uamConfidence = 0.0,
        mealIntent = true,
        physiologicalPhase = PhysiologicalPhase.OFF,
        nowMs = nowMs,
    )

    private fun flat(nowMs: Long) = MealAbsorptionPhaseEngine.Input(
        bgMgdl = 100.0,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 0.0,
        deltaMgdlPer5 = 0.0,
        shortAvgDeltaMgdlPer5 = 0.0,
        combinedDeltaMgdlPer5 = 0.0,
        deltaPrevMgdlPer5 = 0.0,
        mealCobG = 0.0,
        hourOfDay = 12,
        iobU = 1.0,
        maxIobU = 10.0,
        bestTerminalMgdl = 100.0,
        floorTerminalMgdl = 100.0,
        gapPrevMgdl = 0.0,
        heartRateBpm = 72,
        restingHeartRateBpm = 60,
        stepsLast15m = 0,
        uamConfidence = 0.0,
        mealIntent = false,
        physiologicalPhase = PhysiologicalPhase.OFF,
        nowMs = nowMs,
    )

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
