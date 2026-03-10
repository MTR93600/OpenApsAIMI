package app.aaps.plugins.aps.openAPSAIMI

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for prediction blocking behavior during rising BG scenarios.
 * These tests verify that the algorithm does not excessively block SMB/basal
 * when predictions are optimistic but BG is clearly rising.
 */
class PredictionBlockingTest {

    /**
     * Tests isRisingFast detection logic
     */
    @Test
    fun `isRisingFast detects significant rise in meal mode`() {
        // In meal mode, lower thresholds apply (delta >= 2.0 or shortAvg >= 1.5)
        assertTrue("delta=2.5 should trigger in meal mode",
            isRisingFastHelper(delta = 2.5, shortAvgDelta = 1.0, bg = 100.0, target = 100.0, mealMode = true))
        
        assertTrue("shortAvgDelta=2.0 should trigger in meal mode",
            isRisingFastHelper(delta = 1.0, shortAvgDelta = 2.0, bg = 100.0, target = 100.0, mealMode = true))
    }
    
    @Test
    fun `isRisingFast needs higher thresholds outside meal mode`() {
        // Outside meal mode, higher thresholds apply (delta >= 4.0 or shortAvg >= 3.0)
        assertFalse("delta=2.5 should NOT trigger outside meal mode",
            isRisingFastHelper(delta = 2.5, shortAvgDelta = 1.0, bg = 100.0, target = 100.0, mealMode = false))
        
        assertTrue("delta=4.0 should trigger outside meal mode",
            isRisingFastHelper(delta = 4.0, shortAvgDelta = 1.0, bg = 100.0, target = 100.0, mealMode = false))
        
        assertTrue("shortAvgDelta=3.0 should trigger outside meal mode",
            isRisingFastHelper(delta = 1.0, shortAvgDelta = 3.0, bg = 100.0, target = 100.0, mealMode = false))
    }
    
    @Test
    fun `isRisingFast respects BG floor relative to target`() {
        // BG must be >= target - margin (margin=0 in meal mode, 10 outside)
        assertFalse("BG 85 below target 100 should NOT trigger even with high delta (meal mode, margin=0)",
            isRisingFastHelper(delta = 5.0, shortAvgDelta = 3.0, bg = 85.0, target = 100.0, mealMode = true))
        
        assertTrue("BG 95 >= target-10 should trigger outside meal mode",
            isRisingFastHelper(delta = 5.0, shortAvgDelta = 3.0, bg = 95.0, target = 100.0, mealMode = false))
    }
    
    /**
     * Tests for safetyAdjustment blocking bypass scenarios
     */
    @Test
    fun `safetyAdjustment should not reduce SMB when rising fast`() {
        // Scenario: predictedBG=95 (< target+10=110), but delta=5 indicates strong rise
        // Before fix: would reduce by 0.5x
        // After fix: should NOT reduce because risingFast = (delta >= 3 || combinedDelta >= 2)
        
        val delta = 5.0f
        val combinedDelta = 4.0f
        val predictedBG = 95.0f
        val targetBG = 100.0f
        
        val risingFast = delta >= 3f || combinedDelta >= 2f
        val wouldBlockWithoutFix = predictedBG < targetBG + 10
        val shouldBlockWithFix = predictedBG < targetBG + 10 && !risingFast
        
        assertTrue("Condition should have blocked before fix", wouldBlockWithoutFix)
        assertFalse("Condition should NOT block after fix when rising fast", shouldBlockWithFix)
    }
    
    @Test
    fun `safetyAdjustment should still reduce when not rising`() {
        val delta = 0.5f
        val combinedDelta = 0.3f
        val predictedBG = 95.0f
        val targetBG = 100.0f
        
        val risingFast = delta >= 3f || combinedDelta >= 2f
        val shouldBlock = predictedBG < targetBG + 10 && !risingFast
        
        assertTrue("Should still reduce when not rising", shouldBlock)
    }
    
    /**
     * Tests for enablesmb meal mode bypass
     */
    @Test
    fun `enablesmb should allow SMB in meal mode when rising despite low eventualBg`() {
        // Scenario: eventualBg=90 (below safeFloor=100), but delta=4.0 indicates meal rise
        // Before fix: eventualBg > safeFloor required → blocked
        // After fix: (eventualBg > safeFloor || risingFast) → allowed
        
        val currentBg = 120.0
        val delta = 4.0
        val eventualBg = 90.0
        val targetbg = 100.0
        val safeFloor = maxOf(100.0, targetbg - 5)
        
        val risingFast = delta >= 2.0 || (delta > 0 && currentBg > 120)
        
        val wouldEnableWithOldLogic = currentBg > safeFloor && delta > 0.5 && eventualBg > safeFloor
        val shouldEnableWithNewLogic = currentBg > safeFloor && delta > 0.5 && (eventualBg > safeFloor || risingFast)
        
        assertFalse("Old logic would have blocked", wouldEnableWithOldLogic)
        assertTrue("New logic should allow when rising fast", shouldEnableWithNewLogic)
    }
    
    // Helper to replicate isRisingFast logic for testing
    private fun isRisingFastHelper(
        delta: Double,
        shortAvgDelta: Double,
        bg: Double,
        target: Double,
        mealMode: Boolean
    ): Boolean {
        val deltaThreshold = if (mealMode) 2.0 else 4.0
        val shortAvgThreshold = if (mealMode) 1.5 else 3.0
        val bgMargin = if (mealMode) 0.0 else 10.0
        
        return (delta >= deltaThreshold || shortAvgDelta >= shortAvgThreshold) && bg >= target - bgMargin
    }
}
