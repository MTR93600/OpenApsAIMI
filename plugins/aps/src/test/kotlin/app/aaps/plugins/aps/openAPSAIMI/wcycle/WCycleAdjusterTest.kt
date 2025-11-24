package app.aaps.plugins.aps.openAPSAIMI.wcycle

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WCycleAdjusterTest {

    private val prefs: WCyclePreferences = mockk(relaxed = true)
    private val estimator: WCycleEstimator = mockk(relaxed = true)
    private val learner: WCycleLearner = mockk(relaxed = true)
    private val adjuster = WCycleAdjuster(prefs, estimator, learner)

    @Test
    fun `test getInfo disabled`() {
        every { prefs.enabled() } returns false
        val info = adjuster.getInfo()
        assertFalse(info.enabled)
    }

    @Test
    fun `test getInfo enabled`() {
        every { prefs.enabled() } returns true
        every { prefs.trackingMode() } returns CycleTrackingMode.CALENDAR_FIXED_28
        every { prefs.clampMin() } returns 0.5
        every { prefs.clampMax() } returns 1.5
        
        every { estimator.estimate() } returns (0 to CyclePhase.MENSTRUATION)
        every { learner.learnedMultipliers(any(), any(), any()) } returns (1.0 to 1.0)
        
        val info = adjuster.getInfo()
        assertTrue(info.enabled)
        assertEquals(CyclePhase.MENSTRUATION, info.phase)
        assertEquals(1.0, info.basalMultiplier, 0.01)
    }
}
