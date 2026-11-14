package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Android `OmnipodDashPumpPlugin.createFakeTBRWhenNoActivePod`, run by its status checker every
 * minute: with no running pod, AAPS must not count the profile basal as given, so a zero temp basal
 * of the PUMP_SUSPEND type is written, with the serial "4241".
 */
class DashNoPodTempBasalTest {

    private val pumpSync = RecordingDashPumpSync()

    private fun tempBasal(rate: Double) = PumpSync.PumpState.TemporaryBasal(
        timestamp = NOW - 60_000L, duration = 1_800_000L, rate = rate, isAbsolute = true,
        type = PumpSync.TemporaryBasalType.NORMAL, id = 1L, pumpId = NOW - 60_000L
    )

    @Test
    fun `with no pod a zero temp basal is written as Android`() = runTest {
        assertTrue(writeNoPodTempBasal(podRunning = false, pumpSync = pumpSync, nowMs = NOW, aapsLogger = SilentPodLogger))

        assertEquals(
            listOf("tempBasal(timestamp=$NOW, rate=0.0, duration=288000000, isAbsolute=true, type=PUMP_SUSPEND, pumpId=$NOW, pumpType=OMNIPOD_DASH, serial=4241)"),
            pumpSync.calls
        )
    }

    @Test
    fun `a temp basal of another rate is replaced by the zero one`() = runTest {
        pumpSync.expectedTemporaryBasal = tempBasal(1.5)

        assertTrue(writeNoPodTempBasal(podRunning = false, pumpSync = pumpSync, nowMs = NOW, aapsLogger = SilentPodLogger))
        assertEquals(1, pumpSync.calls.size)
    }

    @Test
    fun `nothing is written while a zero temp basal runs or while a pod runs`() = runTest {
        pumpSync.expectedTemporaryBasal = tempBasal(0.0)
        assertFalse(writeNoPodTempBasal(podRunning = false, pumpSync = pumpSync, nowMs = NOW, aapsLogger = SilentPodLogger))

        pumpSync.expectedTemporaryBasal = null
        assertFalse(writeNoPodTempBasal(podRunning = true, pumpSync = pumpSync, nowMs = NOW, aapsLogger = SilentPodLogger))

        assertTrue(pumpSync.calls.isEmpty(), pumpSync.calls.toString())
    }

    private companion object {

        const val NOW = 1_794_564_000_000L
    }
}
