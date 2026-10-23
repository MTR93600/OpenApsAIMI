package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GateKind
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.RiseConfirmation.Level
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiHealthContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matching tests from `dev_OAPSAIMI` @ `ac78a11883`. In `commonTest` with `kotlin.test` and an idle
 * [AimiHealthContext] instead of Mockito, so they also run on the iOS simulator.
 */
class RiseConfirmationTest {

    private fun evaluate(bg: List<Double>): RiseConfirmation.Result = assertNotNull(RiseConfirmation.evaluate(bg))

    @Test
    fun `above 130 two rising ticks confirm`() {
        val result = evaluate(listOf(160.0, 155.0, 150.0))
        assertEquals(2, result.windowTicks)
        assertEquals(Level.CONFIRMED, result.level)
    }

    @Test
    fun `above 130 a flat tick after a rise is not confirmed`() {
        assertEquals(Level.NONE, evaluate(listOf(160.0, 160.0, 150.0)).level)
    }

    @Test
    fun `above 130 a falling latest tick is none`() {
        assertEquals(Level.NONE, evaluate(listOf(158.0, 160.0, 150.0)).level)
    }

    @Test
    fun `at or below 130 the window is 30 minutes`() {
        val steady = listOf(125.0, 121.0, 117.0, 113.0, 109.0, 105.0, 101.0)
        val result = evaluate(steady)
        assertEquals(6, result.windowTicks)
        assertEquals(Level.CONFIRMED, result.level)
    }

    @Test
    fun `at or below 130 a single jump is only weak`() {
        val spike = listOf(125.0, 117.0, 117.0, 117.0, 117.0, 117.0, 117.0)
        assertEquals(Level.WEAK, evaluate(spike).level)
    }

    @Test
    fun `at or below 130 a flat history is none`() {
        val flat = listOf(120.0, 120.0, 121.0, 120.0, 119.0, 120.0, 120.0)
        assertEquals(Level.NONE, evaluate(flat).level)
    }

    @Test
    fun `short history returns null so the caller keeps its old behavior`() {
        assertNull(RiseConfirmation.evaluate(listOf(120.0, 118.0, 116.0)))
        assertNull(RiseConfirmation.evaluate(emptyList()))
    }

    /** Steps and heart rate stay at zero so the activity blocks cannot hide the glycemic branches. */
    private object IdleHealth : AimiHealthContext {
        override fun fetchSnapshot() = HealthContextSnapshot()
        override fun fetchSnapshotForAutodriveGater() = HealthContextSnapshot()
        override fun getLastSnapshot() = HealthContextSnapshot()
    }

    private val gater = AutoDriveGater(IdleHealth, NoOpAapsLogger)

    private val none = RiseConfirmation.Result(Level.NONE, 2, 0.0, 0.0)
    private val confirmed = RiseConfirmation.Result(Level.CONFIRMED, 2, 1.0, 10.0)

    @Test
    fun `a plateau without confirmation waits`() {
        val result = gater.shouldEngageV3(bg = 160.0, combinedDelta = 0.4, riseConfirmation = none)
        assertFalse(result.engage)
        assertEquals(GateKind.AWAITING_CONFIRMATION, result.kind)
    }

    @Test
    fun `a plateau with confirmation engages`() {
        val result = gater.shouldEngageV3(bg = 160.0, combinedDelta = 0.4, riseConfirmation = confirmed)
        assertTrue(result.engage)
    }

    @Test
    fun `very high glucose is corrected without confirmation`() {
        val result = gater.shouldEngageV3(bg = 220.0, combinedDelta = 0.0, riseConfirmation = none)
        assertTrue(result.engage)
    }

    @Test
    fun `a declared meal is never held back`() {
        val result = gater.shouldEngageV3(bg = 160.0, combinedDelta = 1.5, explicitMealMode = true, riseConfirmation = none)
        assertTrue(result.engage)
    }

    @Test
    fun `no confirmation input keeps the old gate`() {
        val result = gater.shouldEngageV3(bg = 160.0, combinedDelta = 0.4)
        assertTrue(result.engage)
    }

    @Test
    fun `an implicit meal context is never held back`() {
        val result = gater.shouldEngageV3(bg = 160.0, combinedDelta = 0.1, cob = 12.0, riseConfirmation = none)
        assertTrue(result.engage)
    }
}
