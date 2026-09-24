package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RealTimeInsulinObserverTest {

    private val observer = RealTimeInsulinObserver()

    @Test
    fun minutesToPeak_20_is_rising() {
        val state = observer.update(
            currentBg = 140.0,
            bgDelta = 1.0,
            iobTotal = 1.5,
            iobActivityNow = 0.6,
            iobActivityIn30 = 0.5,
            minutesToPeak = 20,
            diaHours = 6.0,
            carbsActiveG = 0.0,
            now = 1_000_000L,
        )
        assertEquals(ActivityStage.RISING, state.activityStage)
    }

    @Test
    fun minutesToPeak_10_is_peak() {
        val state = observer.update(
            currentBg = 140.0,
            bgDelta = 0.0,
            iobTotal = 1.5,
            iobActivityNow = 0.8,
            iobActivityIn30 = 0.5,
            minutesToPeak = 10,
            diaHours = 6.0,
            carbsActiveG = 0.0,
            now = 1_000_000L,
        )
        assertEquals(ActivityStage.PEAK, state.activityStage)
    }

    @Test
    fun absolutePeak_75_must_not_force_rising_when_passed_as_minutesToPeak_past() {
        // Past peak: minutesToPeak <= 0 → FALLING or TAIL, never permanent RISING from abs peak 75.
        val state = observer.update(
            currentBg = 120.0,
            bgDelta = -1.0,
            iobTotal = 1.2,
            iobActivityNow = 0.4,
            iobActivityIn30 = 0.2,
            minutesToPeak = 0,
            diaHours = 6.0,
            carbsActiveG = 0.0,
            now = 1_000_000L,
        )
        assertEquals(ActivityStage.FALLING, state.activityStage)
    }
}
