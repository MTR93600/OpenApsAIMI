package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InsulinWeibullCurveTest {

    @Test
    fun `normalized activity peaks at one near configured peak time`() {
        val peak = 75.0
        val atPeak = InsulinWeibullCurve.activityNormalized(peak, peak)
        assertEquals(1.0, atPeak, 0.02)
        val early = InsulinWeibullCurve.activityNormalized(5.0, peak)
        val late = InsulinWeibullCurve.activityNormalized(200.0, peak)
        assertTrue(early < atPeak)
        assertTrue(late < atPeak)
    }

    @Test
    fun `activity stage follows time bands`() {
        val peak = 60.0
        assertEquals(ActivityStage.RISING, InsulinWeibullCurve.activityStage(10.0, peak, 1.0))
        assertEquals(ActivityStage.PEAK, InsulinWeibullCurve.activityStage(50.0, peak, 1.0))
        assertEquals(ActivityStage.FALLING, InsulinWeibullCurve.activityStage(120.0, peak, 1.0))
        assertEquals(ActivityStage.TAIL, InsulinWeibullCurve.activityStage(0.0, peak, 0.02))
    }
}
