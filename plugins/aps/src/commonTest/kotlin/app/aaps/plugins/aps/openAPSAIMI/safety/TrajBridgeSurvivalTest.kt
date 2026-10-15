package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The numbers come from the real tick of 2026-10-02 21:14: the trajectory bridge asked for 0.13 U/h
 * (tag `STACKING_SPIRAL`) and the pump received 4.84 U/h because the basal schedule ran afterwards and
 * wrote over the request.
 */
class TrajBridgeSurvivalTest {

    private val scheduled = 4.84
    private val requested = 0.13

    @Test
    fun `key off keeps the scheduled rate and only records what it would have done`() {
        val d = TrajBridgeSurvival.resolve(scheduled, requested, keyArmed = false)

        assertEquals(scheduled, d.rateUph, 1e-9)
        assertFalse(d.survived)
        // The number is still recorded, because the decision to arm will be taken from it.
        assertNotNull(d.wouldReduceToUph)
        assertEquals(requested, d.wouldReduceToUph!!, 1e-9)
    }

    @Test
    fun `key on applies the reduction`() {
        val d = TrajBridgeSurvival.resolve(scheduled, requested, keyArmed = true)

        assertEquals(requested, d.rateUph, 1e-9)
        assertTrue(d.survived)
    }

    @Test
    fun `a request above the schedule never raises the rate`() {
        val d = TrajBridgeSurvival.resolve(scheduledUph = 1.20, requestedUph = 3.50, keyArmed = true)

        assertEquals(1.20, d.rateUph, 1e-9)
        assertFalse(d.survived)
        // Nothing would have been reduced, so there is no number to report.
        assertNull(d.wouldReduceToUph)
    }

    @Test
    fun `a request equal to the schedule changes nothing`() {
        val d = TrajBridgeSurvival.resolve(scheduledUph = 2.0, requestedUph = 2.0, keyArmed = true)

        assertEquals(2.0, d.rateUph, 1e-9)
        assertFalse(d.survived)
    }

    @Test
    fun `no request at all leaves the tick untouched`() {
        val d = TrajBridgeSurvival.resolve(scheduled, requestedUph = null, keyArmed = true)

        assertEquals(scheduled, d.rateUph, 1e-9)
        assertFalse(d.survived)
        assertNull(d.wouldReduceToUph)
    }

    @Test
    fun `a broken number can never decide a rate`() {
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.0)) {
            val d = TrajBridgeSurvival.resolve(scheduled, bad, keyArmed = true)
            assertEquals(scheduled, d.rateUph, 1e-9)
            assertFalse(d.survived)
        }
        val brokenSchedule = TrajBridgeSurvival.resolve(Double.NaN, requested, keyArmed = true)
        assertFalse(brokenSchedule.survived)
        assertNull(brokenSchedule.wouldReduceToUph)
    }

    @Test
    fun `it can only ever lower a rate`() {
        val rates = listOf(0.0, 0.13, 0.55, 2.0, 4.84, 7.0)
        for (scheduledUph in rates) {
            for (requestedUph in rates) {
                for (armed in listOf(true, false)) {
                    val d = TrajBridgeSurvival.resolve(scheduledUph, requestedUph, armed)
                    assertTrue(d.rateUph <= scheduledUph)
                }
            }
        }
    }
}
