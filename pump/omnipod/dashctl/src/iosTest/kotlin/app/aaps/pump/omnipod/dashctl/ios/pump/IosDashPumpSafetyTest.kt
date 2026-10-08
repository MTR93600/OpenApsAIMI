package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.session.DashMessageTransport
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Safety guard: the pump must NEVER send bytes without a paired pod
 * AND an established session.
 *
 * Uses a recording transport; any send attempt without pairing+session
 * is a failure. Runs on device/simulator.
 */
class IosDashPumpSafetyTest {

    private class RecordingTransport : DashMessageTransport {
        val sent = mutableListOf<ByteArray>()
        override fun send(data: ByteArray): Boolean {
            sent += data
            return true
        }
        override fun receive(): ByteArray? = null
    }

    private class MemoryStore : DashPodStateStore {
        var state: DashPodState? = null
        override fun load(): DashPodState? = state
        override fun save(state: DashPodState) { this.state = state }
        override fun clear() { state = null }
    }

    private fun freshPump(): Triple<IosDashPump, RecordingTransport, DashPodStateManager> {
        val transport = RecordingTransport()
        val stateManager = DashPodStateManager(MemoryStore())
        val pump = IosDashPump(transport = transport, stateManager = stateManager)
        return Triple(pump, transport, stateManager)
    }

    private fun fakeBolus(insulin: Double): DetailedBolusInfo =
        DetailedBolusInfo().apply { this.insulin = insulin }

    @Test
    fun `unpaired pump refuses bolus and sends nothing`() = runTest {
        val (pump, transport, _) = freshPump()
        val result = pump.deliverTreatment(fakeBolus(1.0))
        assertFalse(result.success, "bolus must fail when unpaired")
        assertTrue(transport.sent.isEmpty(), "no bytes may go to the radio when unpaired")
    }

    @Test
    fun `unpaired pump refuses temp basal and sends nothing`() = runTest {
        val (pump, transport, _) = freshPump()
        val result = pump.setTempBasalAbsolute(
            1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL
        )
        assertFalse(result.success)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `unpaired pump refuses cancel temp basal`() = runTest {
        val (pump, transport, _) = freshPump()
        val result = pump.cancelTempBasal(false)
        assertFalse(result.success)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `paired without session refuses commands`() = runTest {
        val (pump, transport, stateManager) = freshPump()
        stateManager.updateFromPairing(12345L, ByteArray(16) { it.toByte() })
        pump.connect("test")
        // No establishSession(): guard must fail closed.
        val result = pump.deliverTreatment(fakeBolus(1.0))
        assertFalse(result.success)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `paired with session but disconnected refuses commands`() = runTest {
        val (pump, transport, stateManager) = freshPump()
        stateManager.updateFromPairing(12345L, ByteArray(16) { it.toByte() })
        // Connected then disconnected; no session ever established.
        pump.connect("test")
        pump.disconnect("test")
        val result = pump.deliverTreatment(fakeBolus(1.0))
        assertFalse(result.success)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `stopDelivery refuses when unpaired`() {
        val (pump, transport, _) = freshPump()
        val result = pump.stopDelivery()
        assertFalse(result.success)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `isConfigured reflects pairing state`() {
        val (unpaired, _, _) = freshPump()
        assertFalse(unpaired.isConfigured())
        val (paired, _, stateManager) = freshPump()
        stateManager.updateFromPairing(12345L, ByteArray(16))
        assertTrue(paired.isConfigured())
        assertFalse(paired.isInitialized(), "no session yet")
    }
}
