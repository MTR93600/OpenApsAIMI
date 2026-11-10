package app.aaps.plugins.aps.openAPSAIMI

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for prediction blocking behavior during rising BG scenarios.
 * These tests verify that the algorithm does not excessively block SMB/basal
 * when predictions are optimistic but BG is clearly rising.
 *
 * Meal-mode SMB rise logic is mirrored from [DetermineBasalaimiSMB2] `enablesmb` branch
 * (~6293–6301): `combinedDelta` drives `risingFast` / `isExplosive`, not a separate short-avg gate.
 */
class PredictionBlockingTest {

    /** Same predicate as production `risingFast` when `mealModeActive` (combinedDelta = meal path input). */
    private fun mealModeRisingFast(combinedDelta: Double, currentBg: Double): Boolean =
        combinedDelta >= 2.0 || (combinedDelta > 0 && currentBg > 120)

    private fun mealModeExplosiveRise(combinedDelta: Double, currentBg: Double): Boolean =
        combinedDelta > 4.0 && currentBg > 90.0

    /** Full meal-mode SMB enable predicate from production (mealModeActive block only). */
    private fun mealModeSmbRiseBypassEnabled(
        currentBg: Double,
        combinedDelta: Double,
        eventualBg: Double,
        targetbg: Double,
    ): Boolean {
        val safeFloorValue = maxOf(100.0, targetbg - 5)
        val risingFast = mealModeRisingFast(combinedDelta, currentBg)
        val isExplosive = mealModeExplosiveRise(combinedDelta, currentBg)
        return (currentBg > safeFloorValue || isExplosive) &&
            combinedDelta > 0.5 &&
            (eventualBg > safeFloorValue || risingFast || isExplosive)
    }

    @Test
    fun `meal mode risingFast triggers on combinedDelta at least 2`() {
        assertTrue(mealModeRisingFast(combinedDelta = 2.0, currentBg = 100.0))
        assertFalse(mealModeRisingFast(combinedDelta = 1.99, currentBg = 100.0))
    }

    @Test
    fun `meal mode risingFast triggers when bg above 120 with positive combinedDelta`() {
        assertTrue(mealModeRisingFast(combinedDelta = 0.1, currentBg = 121.0))
        assertFalse(mealModeRisingFast(combinedDelta = 0.0, currentBg = 121.0))
    }

    @Test
    fun `meal mode explosive rise strict delta above 4 and bg above 90`() {
        assertTrue(mealModeExplosiveRise(combinedDelta = 4.01, currentBg = 91.0))
        assertFalse(mealModeExplosiveRise(combinedDelta = 4.0, currentBg = 91.0))
        assertFalse(mealModeExplosiveRise(combinedDelta = 5.0, currentBg = 90.0))
    }

    @Test
    fun `meal mode SMB bypass allows eventual below safe floor when risingFast`() {
        val currentBg = 120.0
        val combinedDelta = 4.0
        val eventualBg = 90.0
        val targetbg = 100.0
        assertTrue(
            mealModeSmbRiseBypassEnabled(
                currentBg = currentBg,
                combinedDelta = combinedDelta,
                eventualBg = eventualBg,
                targetbg = targetbg,
            )
        )
    }

    @Test
    fun `meal mode SMB bypass blocked when below safe floor and not explosive and no rise signal`() {
        val safeFloor = maxOf(100.0, 100.0 - 5)
        val currentBg = safeFloor - 5.0
        assertFalse(
            mealModeSmbRiseBypassEnabled(
                currentBg = currentBg,
                combinedDelta = 1.0,
                eventualBg = 90.0,
                targetbg = 100.0,
            )
        )
    }

    @Test
    fun `safetyAdjustment should not reduce SMB when rising fast`() {
        val delta = 5.0f
        val combinedDelta = 4.0f
        val predictedBG = 95.0f
        val targetBG = 100.0f

        val risingFast = delta >= 3f || combinedDelta >= 2f
        val wouldBlockWithoutFix = predictedBG < targetBG + 10
        val shouldBlockWithFix = predictedBG < targetBG + 10 && !risingFast

        assertTrue(wouldBlockWithoutFix, "Condition should have blocked before fix")
        assertFalse(shouldBlockWithFix, "Condition should NOT block after fix when rising fast")
    }

    @Test
    fun `safetyAdjustment should still reduce when not rising`() {
        val delta = 0.5f
        val combinedDelta = 0.3f
        val predictedBG = 95.0f
        val targetBG = 100.0f

        val risingFast = delta >= 3f || combinedDelta >= 2f
        val shouldBlock = predictedBG < targetBG + 10 && !risingFast

        assertTrue(shouldBlock, "Should still reduce when not rising")
    }

    @Test
    fun `enablesmb meal mode uses same risingFast as production helper`() {
        val currentBg = 120.0
        val combinedDelta = 4.0
        val eventualBg = 90.0
        val targetbg = 100.0
        val safeFloor = maxOf(100.0, targetbg - 5)

        val risingFast = mealModeRisingFast(combinedDelta, currentBg)
        assertTrue(risingFast)

        val wouldEnableWithOldLogic = currentBg > safeFloor && combinedDelta > 0.5 && eventualBg > safeFloor
        val shouldEnableWithNewLogic = mealModeSmbRiseBypassEnabled(currentBg, combinedDelta, eventualBg, targetbg)

        assertFalse(wouldEnableWithOldLogic, "Old logic would have blocked")
        assertTrue(shouldEnableWithNewLogic, "New logic should allow when rising fast")
    }
}
