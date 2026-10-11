package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The cadence of the one thing that feeds the rolling baseline on iOS.
 *
 * Too often and the baseline is fed the same readings again; too rarely and the physio path goes
 * quiet. Both move insulin, so the rule is tested rather than trusted.
 */
class PhysioFeedCadenceTest {

    private val start = 1_000_000L

    @Test
    fun `the first tick runs the one day window`() {
        val cadence = PhysioFeedCadence(start)
        assertEquals(PhysioFeedWindow.REALTIME, cadence.due(start))
    }

    @Test
    fun `nothing is due again straight after a run`() {
        val cadence = PhysioFeedCadence(start)
        cadence.markRan(PhysioFeedWindow.REALTIME, start)
        assertNull(cadence.due(start))
        assertNull(cadence.due(start + PhysioFeedCadence.REALTIME_PERIOD_MS - 1))
    }

    @Test
    fun `the one day window comes back after fifteen minutes`() {
        val cadence = PhysioFeedCadence(start)
        cadence.markRan(PhysioFeedWindow.REALTIME, start)
        val next = start + PhysioFeedCadence.REALTIME_PERIOD_MS
        assertEquals(PhysioFeedWindow.REALTIME, cadence.due(next))
    }

    @Test
    fun `the three day window wins after thirty minutes`() {
        val cadence = PhysioFeedCadence(start)
        cadence.markRan(PhysioFeedWindow.REALTIME, start)
        val next = start + PhysioFeedCadence.METABOLIC_PERIOD_MS
        assertEquals(PhysioFeedWindow.METABOLIC, cadence.due(next))
    }

    @Test
    fun `the seven day window wins after a day and is the only one that asks the model`() {
        val cadence = PhysioFeedCadence(start)
        val next = start + PhysioFeedCadence.DAILY_PERIOD_MS
        assertEquals(PhysioFeedWindow.DAILY, cadence.due(next))
        assertEquals(true, PhysioFeedWindow.DAILY.runLLM)
        assertEquals(false, PhysioFeedWindow.METABOLIC.runLLM)
        assertEquals(false, PhysioFeedWindow.REALTIME.runLLM)
    }

    @Test
    fun `a wide run also satisfies the narrow windows`() {
        val cadence = PhysioFeedCadence(start)
        val dailyAt = start + PhysioFeedCadence.DAILY_PERIOD_MS
        cadence.markRan(PhysioFeedWindow.DAILY, dailyAt)
        assertNull(cadence.due(dailyAt))
        assertNull(cadence.due(dailyAt + PhysioFeedCadence.REALTIME_PERIOD_MS - 1))
        assertEquals(PhysioFeedWindow.REALTIME, cadence.due(dailyAt + PhysioFeedCadence.REALTIME_PERIOD_MS))
    }

    @Test
    fun `a narrow run does not satisfy the wide windows`() {
        val cadence = PhysioFeedCadence(start)
        cadence.markRan(PhysioFeedWindow.REALTIME, start + PhysioFeedCadence.DAILY_PERIOD_MS)
        // The daily clock was never moved, so the next tick still owes a seven day run.
        assertEquals(PhysioFeedWindow.DAILY, cadence.due(start + PhysioFeedCadence.DAILY_PERIOD_MS + 1))
    }

    @Test
    fun `a clock moved backwards makes a run due rather than stalling the path`() {
        val cadence = PhysioFeedCadence(start)
        cadence.markRan(PhysioFeedWindow.DAILY, start)
        assertEquals(PhysioFeedWindow.DAILY, cadence.due(start - 1))
    }

    @Test
    fun `each window reads the number of days Android reads`() {
        assertEquals(1, PhysioFeedWindow.REALTIME.daysBack)
        assertEquals(3, PhysioFeedWindow.METABOLIC.daysBack)
        assertEquals(7, PhysioFeedWindow.DAILY.daysBack)
    }
}
