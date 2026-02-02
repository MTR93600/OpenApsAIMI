package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class BasalFirstPolicyTest {

    // Mimic the logic inside DetermineBasalAIMI2 for verification
    private fun isBasalFirstActive(
        learnerFactor: Double,
        bg: Double,
        delta: Double,
        isMealAdvisorOneShot: Boolean
    ): Boolean {
        val isFragileBg = bg < 110.0 && delta < 0.0
        val isLearnerPrudent = learnerFactor < 0.75
        
        return (isLearnerPrudent || isFragileBg) && !isMealAdvisorOneShot
    }

    @Test
    fun `test Learner Prudence triggers Basal First`() {
        // Factor 0.5 (Low) -> Should be ACTIVE (True)
        assertTrue(isBasalFirstActive(0.5, 120.0, 0.0, false))
        
        // Factor 0.74 (Low) -> Should be ACTIVE (True)
        assertTrue(isBasalFirstActive(0.74, 120.0, 0.0, false))
        
        // Factor 0.8 (Normal) -> Should be INACTIVE (False)
        assertFalse(isBasalFirstActive(0.8, 120.0, 0.0, false))
    }

    @Test
    fun `test Fragile BG triggers Basal First`() {
        // BG 90, Delta -2 (Fragile) -> Should be ACTIVE (True)
        assertTrue(isBasalFirstActive(1.0, 90.0, -2.0, false))
        
        // BG 90, Delta +1 (Recovering) -> Should be INACTIVE (False)
        assertFalse(isBasalFirstActive(1.0, 90.0, 1.0, false))
        
        // BG 120, Delta -2 (Safe range) -> Should be INACTIVE (False)
        assertFalse(isBasalFirstActive(1.0, 120.0, -2.0, false))
    }

    @Test
    fun `test Advisor OneShot bypasses Basal First`() {
        // Learner Low (0.5), but Advisor Triggered -> Should be INACTIVE (False)
        // because we want to allow the manual/advisor OneShot to proceed
        assertFalse(isBasalFirstActive(0.5, 120.0, 0.0, true))
        
        // Fragile BG (90, -2), but Advisor Triggered -> Should be INACTIVE (False)
        // (Assuming advisor knows what it's doing, e.g. covering a meal just eaten)
        assertFalse(isBasalFirstActive(1.0, 90.0, -2.0, true))
    }
}
