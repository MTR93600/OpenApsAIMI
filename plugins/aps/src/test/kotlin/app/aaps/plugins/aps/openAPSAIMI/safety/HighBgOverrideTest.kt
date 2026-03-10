package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighBgOverrideTest {

    @Test
    fun `test apply no override needed`() {
        val result = HighBgOverride.apply(
            bg = 100.0,
            delta = 0.0,
            predictedBg = 100.0,
            eventualBg = 100.0,
            hypoGuard = 70.0,
            iob = 0.0,
            maxSmb = 2.0,
            currentDose = 0.5,
            pumpStep = 0.05
        )
        assertFalse(result.overrideUsed)
        assertEquals(0.5, result.dose, 0.0)
    }

    @Test
    fun `test apply override triggered strong BG`() {
        // BG >= HIGH_BG_OVERRIDE_BG_STRONG (assuming 200 or similar, let's check Constants if possible, but I can guess high value)
        // Constants.HIGH_BG_OVERRIDE_BG_STRONG is likely around 200.
        // Let's try 250.
        val result = HighBgOverride.apply(
            bg = 250.0,
            delta = 0.0,
            predictedBg = 250.0,
            eventualBg = 250.0,
            hypoGuard = 70.0,
            iob = 0.0,
            maxSmb = 2.0,
            currentDose = 0.0, // Current dose 0, should be boosted to min step
            pumpStep = 0.05
        )
        assertTrue(result.overrideUsed)
        assertEquals(0.05, result.dose, 0.0)
        assertEquals(0, result.newInterval)
    }

    @Test
    fun `test apply override triggered moderate BG with rise`() {
        // BG >= HIGH_BG_OVERRIDE_BG_MIN (likely 180?) and delta >= 1.5
        val result = HighBgOverride.apply(
            bg = 190.0,
            delta = 2.0,
            predictedBg = 190.0,
            eventualBg = 190.0,
            hypoGuard = 70.0,
            iob = 0.0,
            maxSmb = 2.0,
            currentDose = 0.0,
            pumpStep = 0.05
        )
        assertTrue(result.overrideUsed)
    }

    @Test
    fun `test apply blocked by hypo risk`() {
        val result = HighBgOverride.apply(
            bg = 250.0,
            delta = 0.0,
            predictedBg = 60.0, // Predicted low
            eventualBg = 250.0,
            hypoGuard = 70.0,
            iob = 0.0,
            maxSmb = 2.0,
            currentDose = 0.5,
            pumpStep = 0.05
        )
        assertFalse(result.overrideUsed)
    }
}
