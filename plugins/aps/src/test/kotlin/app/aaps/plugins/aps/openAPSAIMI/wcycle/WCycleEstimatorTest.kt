package app.aaps.plugins.aps.openAPSAIMI.wcycle

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WCycleEstimatorTest {

    private val prefs: WCyclePreferences = mockk(relaxed = true)
    private val estimator = WCycleEstimator(prefs)

    @Test
    fun `test estimate MENOPAUSE`() {
        every { prefs.trackingMode() } returns CycleTrackingMode.MENOPAUSE
        val (day, phase) = estimator.estimate()
        assertEquals(0, day)
        assertEquals(CyclePhase.UNKNOWN, phase)
    }

    @Test
    fun `test estimate NO_MENSES_LARC`() {
        every { prefs.trackingMode() } returns CycleTrackingMode.NO_MENSES_LARC
        val (day, phase) = estimator.estimate()
        assertEquals(0, day)
        assertEquals(CyclePhase.LUTEAL, phase)
    }

    @Test
    fun `test estimate CALENDAR_FIXED_28`() {
        every { prefs.trackingMode() } returns CycleTrackingMode.CALENDAR_FIXED_28
        every { prefs.startDom() } returns 1
        
        // Test with a specific date: 2023-01-05 (Day 4, Menstruation)
        val date = LocalDate.of(2023, 1, 5)
        val (day, phase) = estimator.estimate(date)
        
        // Start DOM is 1. Current is 5. Days elapsed = 4.
        // Day index = 4.
        // Phase for day 4 is MENSTRUATION (0..4).
        assertEquals(4, day)
        assertEquals(CyclePhase.MENSTRUATION, phase)
    }
}
