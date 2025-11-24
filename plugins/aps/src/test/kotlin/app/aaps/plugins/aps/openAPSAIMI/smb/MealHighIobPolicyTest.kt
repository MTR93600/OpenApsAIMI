package app.aaps.plugins.aps.openAPSAIMI.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealHighIobPolicyTest {

    @Test
    fun `test computeMealHighIobDecision not active`() {
        val result = computeMealHighIobDecision(
            mealModeActive = false,
            bg = 150.0,
            delta = 5.0,
            eventualBg = 150.0,
            targetBg = 100.0,
            iob = 5.0,
            maxIob = 2.0
        )
        assertFalse(result.relax)
        assertEquals(1.0, result.damping, 0.0)
    }

    @Test
    fun `test computeMealHighIobDecision active but not eligible`() {
        // bg <= max(120, target)
        val result = computeMealHighIobDecision(
            mealModeActive = true,
            bg = 100.0,
            delta = 5.0,
            eventualBg = 150.0,
            targetBg = 100.0,
            iob = 5.0,
            maxIob = 2.0
        )
        assertFalse(result.relax)
    }

    @Test
    fun `test computeMealHighIobDecision active and eligible`() {
        // bg > 120, delta > 0.5, eventual > target+10, iob > maxIob
        // slack = 2.0 * 0.3 = 0.6
        // iob = 2.3 (maxIob + 0.5*slack)
        // excessFraction = (2.3 - 2.0) / 0.6 = 0.3 / 0.6 = 0.5
        // damping = 1.0 - 0.5 * 0.5 = 0.75
        
        val result = computeMealHighIobDecision(
            mealModeActive = true,
            bg = 150.0,
            delta = 1.0,
            eventualBg = 150.0,
            targetBg = 100.0,
            iob = 2.3,
            maxIob = 2.0
        )
        assertTrue(result.relax)
        assertEquals(0.75, result.damping, 0.01)
    }
}
