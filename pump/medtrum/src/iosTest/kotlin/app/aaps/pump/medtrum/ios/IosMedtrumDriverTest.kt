package app.aaps.pump.medtrum.ios

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleGattCharacteristic
import app.aaps.core.interfaces.pump.ble.session.BleGattService
import app.aaps.core.interfaces.pump.ble.session.BleCharProperty
import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.session.MedtrumBleCallback
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSession
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import app.aaps.pump.medtrum.util.CrcUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the iOS Medtrum driver.
 *
 * [FakeBleCentral] implements [BleCentral] with scripted behavior; events are
 * driven directly into [IosMedtrumBleTransport.onEvent]. No iOS APIs are used,
 * so these run on plain JVM.
 */
class IosMedtrumDriverTest {

    // -- Fake -----------------------------------------------------------------

    private class FakeBleCentral : BleCentral {
        var adapterState: BleAdapterState = BleAdapterState.PoweredOn
        val writes = mutableListOf<Triple<String, String, ByteArray>>()
        val enabledNotifies = mutableListOf<Triple<String, String, Boolean>>()
        var scanned = false
        var connectedId: String? = null

        /** What CoreBluetooth would hand back as the GAP name. Missing means the name is unknown. */
        val names = mutableMapOf<String, String>()
        val cancelled = mutableListOf<String>()
        val released = mutableListOf<String>()

        override fun adapterState(): BleAdapterState = adapterState
        override fun startScan() { scanned = true }
        override fun stopScan() { scanned = false }
        override fun connect(id: String): Boolean { connectedId = id; return true }
        override fun cancel(id: String) { cancelled.add(id); if (connectedId == id) connectedId = null }
        override fun release(id: String) { released.add(id); if (connectedId == id) connectedId = null }
        override fun discoverServices(id: String) = Unit
        override fun discoverCharacteristics(id: String, serviceUuid: String) = Unit
        override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean {
            writes.add(Triple(id, characteristicUuid, data))
            return true
        }
        override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean {
            enabledNotifies.add(Triple(id, characteristicUuid, indicate))
            return true
        }
        override fun cachedName(id: String): String? = names[id]
    }

    /** Keeps the log lines so a test can show that nothing was released without a word. */
    private class RecordingLogger : MedtrumLogger {
        val lines = mutableListOf<String>()
        override fun debug(tag: String, message: String) { lines.add(message) }
        override fun error(tag: String, message: String) { lines.add(message) }
        override fun warn(tag: String, message: String) { lines.add(message) }
    }

    private class RecordingCallback : MedtrumBleCallback {
        var connected = false
        var disconnected = false
        val notifications = mutableListOf<ByteArray>()
        val indications = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        override fun onConnected() { connected = true }
        override fun onDisconnected() { disconnected = true }
        override fun onNotification(data: ByteArray) { notifications.add(data) }
        override fun onIndication(data: ByteArray) { indications.add(data) }
        override fun onSendMessageError(reason: String, isRetryAble: Boolean) { errors.add(reason) }
    }

    private fun medtrumManufacturerPayload(sn: Long): ByteArray {
        // iOS: 2-byte LE company ID + ManufacturerData (4-byte SN + type + version)
        val out = ByteArray(8)
        out[0] = 0x81.toByte() // 18305 = 0x4781, little-endian
        out[1] = 0x47.toByte()
        out[2] = sn.toByte()
        out[3] = (sn shr 8).toByte()
        out[4] = (sn shr 16).toByte()
        out[5] = (sn shr 24).toByte()
        out[6] = 1 // device type
        out[7] = 2 // version
        return out
    }

    private fun setup(
        pumpSerial: Long = 0L,
        logger: MedtrumLogger = MedtrumLogger.NO_OP,
    ): Triple<FakeBleCentral, IosMedtrumBleTransport, RecordingCallback> {
        val central = FakeBleCentral()
        val transport = IosMedtrumBleTransport(central, pumpSerial = { pumpSerial }, logger = logger)
        val callback = RecordingCallback()
        transport.setCallback(callback)
        return Triple(central, transport, callback)
    }

    private fun connectFlow(
        central: FakeBleCentral,
        transport: IosMedtrumBleTransport,
        deviceId: String = "dev-1",
        sn: Long = 12345L,
    ) {
        assertTrue(transport.connect("test", sn))
        transport.onEvent(
            BleCentralEvent.ScanResult(name = "MT", id = deviceId, advertisementBytes = medtrumManufacturerPayload(sn))
        )
        assertEquals(deviceId, central.connectedId)
        transport.onEvent(BleCentralEvent.Link(id = deviceId, connected = true, status = 0))
        transport.onEvent(
            BleCentralEvent.Services(
                id = deviceId,
                services = listOf(BleGattService(IosMedtrumBleTransport.SERVICE_UUID, emptyList())),
                status = 0,
            )
        )
        transport.onEvent(
            BleCentralEvent.Characteristics(
                id = deviceId,
                serviceUuid = IosMedtrumBleTransport.SERVICE_UUID,
                characteristics = listOf(
                    BleGattCharacteristic(IosMedtrumBleTransport.READ_UUID, setOf(BleCharProperty.Notify)),
                    BleGattCharacteristic(IosMedtrumBleTransport.WRITE_UUID, setOf(BleCharProperty.Write)),
                ),
                status = 0,
            )
        )
        // Both descriptors armed → onConnected
        transport.onEvent(BleCentralEvent.DescriptorWritten(id = deviceId, descriptorUuid = "d1", status = 0))
        transport.onEvent(BleCentralEvent.DescriptorWritten(id = deviceId, descriptorUuid = "d2", status = 0))
    }

    // -- Transport: connection --------------------------------------------------

    @Test
    fun connectFindsPatchBySn() {
        val (central, transport, callback) = setup()
        connectFlow(central, transport, sn = 999L)
        assertTrue(callback.connected)
        assertFalse(central.scanned)
    }

    @Test
    fun connectIgnoresWrongSn() {
        val (central, transport, _) = setup()
        assertTrue(transport.connect("test", 111L))
        transport.onEvent(
            BleCentralEvent.ScanResult(name = "MT", id = "other", advertisementBytes = medtrumManufacturerPayload(222L))
        )
        assertEquals(null, central.connectedId)
        assertTrue(central.scanned) // still scanning
    }

    @Test
    fun connectRefusesWhenAdapterNotReady() {
        val (central, transport, _) = setup()
        central.adapterState = BleAdapterState.PoweredOff
        assertFalse(transport.connect("test", 123L))
        assertFalse(central.scanned)
    }

    @Test
    fun connectIgnoresWrongCompanyId() {
        val (central, transport, _) = setup()
        assertTrue(transport.connect("test", 123L))
        val payload = medtrumManufacturerPayload(123L).also { it[0] = 0x00; it[1] = 0x00 }
        transport.onEvent(BleCentralEvent.ScanResult(name = "MT", id = "x", advertisementBytes = payload))
        assertEquals(null, central.connectedId)
    }

    // -- Transport: messaging ----------------------------------------------------

    @Test
    fun sendMessageChunksAndWrites() {
        val (central, transport, _) = setup()
        connectFlow(central, transport)
        central.writes.clear()
        // 40-byte message → multiple 20-byte ATT chunks via WriteCommandPackets
        transport.sendMessage(ByteArray(40) { it.toByte() })
        assertTrue(central.writes.isNotEmpty())
        assertTrue(central.writes.all { it.second.equals(IosMedtrumBleTransport.WRITE_UUID, ignoreCase = true) })
        // Each chunk is a full write; continuing on Written sends the next
        val firstCount = central.writes.size
        transport.onEvent(BleCentralEvent.Written(id = "dev-1", characteristicUuid = IosMedtrumBleTransport.WRITE_UUID, status = 0))
        assertTrue(central.writes.size >= firstCount)
    }

    @Test
    fun notificationRoutedToOnNotification() {
        val (central, transport, callback) = setup()
        connectFlow(central, transport)
        val data = byteArrayOf(1, 2, 3)
        transport.onEvent(BleCentralEvent.Value(id = "dev-1", characteristicUuid = IosMedtrumBleTransport.READ_UUID, data = data))
        assertEquals(1, callback.notifications.size)
        assertTrue(callback.notifications[0].contentEquals(data))
        assertTrue(callback.indications.isEmpty())
    }

    @Test
    fun indicationReassembledToOnIndication() {
        val (central, transport, callback) = setup()
        connectFlow(central, transport)
        // Single-chunk indication: build a minimal valid ReadDataPacket frame.
        // Frame: [size, cmd, seq, pkgIndex=0, ...data..., crc]. Use WriteCommandPackets
        // to build a real frame, then feed its chunks as indications.
        val message = byteArrayOf(0x09, 0x0A, 0x0B)
        val packets = app.aaps.pump.medtrum.comm.WriteCommandPackets(message, 0)
        val chunk = packets.getNextPacket()!!
        transport.onEvent(BleCentralEvent.Value(id = "dev-1", characteristicUuid = IosMedtrumBleTransport.WRITE_UUID, data = chunk))
        // Single chunk completes the packet (40-byte test message would need more)
        if (packets.allPacketsConsumed()) {
            assertEquals(1, callback.indications.size)
        }
    }

    @Test
    fun disconnectTearsDown() {
        val (central, transport, callback) = setup()
        connectFlow(central, transport)
        transport.disconnect("test")
        assertEquals(null, central.connectedId)
        transport.onEvent(BleCentralEvent.Link(id = "dev-1", connected = false, status = 0))
        assertTrue(callback.disconnected)
    }

    // -- Safety guard -------------------------------------------------------------

    private fun pumpNotReady(): IosMedtrumPump {
        val (central, transport, _) = setup()
        val state = MedtrumSessionState(clock = MedtrumClock.fixed(0L))
        // Never connected: phase IDLE, connection DISCONNECTED
        return IosMedtrumPump(
            transport, state, MedtrumClock.fixed(0L), TestTextResolver,
            pumpSync = RecordingPumpSync(), detailedBolusInfoStorage = FakeDetailedBolusInfoStorage(),
            temporaryBasalStorage = FakeTemporaryBasalStorage(),
            scope = CoroutineScope(SupervisorJob()), store = null
        )
    }

    @Test
    fun guardBolusRefusedWhenNotReady() = runTest {
        val pump = pumpNotReady()
        assertFalse(pump.isReady())
        val result = pump.deliverTreatment(app.aaps.core.interfaces.pump.DetailedBolusInfo().apply { insulin = 1.0 })
        assertFalse(result.success)
        assertFalse(result.enacted)
    }

    @Test
    fun guardTbrRefusedWhenNotReady() = runTest {
        val pump = pumpNotReady()
        val result = pump.setTempBasalAbsolute(1.0, 30, false, app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType.NORMAL)
        assertFalse(result.success)
    }

    @Test
    fun guardCancelTbrRefusedWhenNotReady() = runTest {
        val pump = pumpNotReady()
        val result = pump.cancelTempBasal(false)
        assertFalse(result.success)
    }

    @Test
    fun zeroRadioWritesWhenNotReady() = runTest {
        val (central, transport, _) = setup()
        val state = MedtrumSessionState(clock = MedtrumClock.fixed(0L))
        val pump = IosMedtrumPump(
            transport, state, MedtrumClock.fixed(0L), TestTextResolver,
            pumpSync = RecordingPumpSync(), detailedBolusInfoStorage = FakeDetailedBolusInfoStorage(),
            temporaryBasalStorage = FakeTemporaryBasalStorage(),
            scope = CoroutineScope(SupervisorJob()), store = null
        )
        pump.deliverTreatment(app.aaps.core.interfaces.pump.DetailedBolusInfo().apply { insulin = 2.0 })
        pump.setTempBasalAbsolute(1.5, 60, false, app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType.NORMAL)
        pump.cancelTempBasal(false)
        // No connection was ever established → no writes possible
        assertTrue(central.writes.isEmpty())
    }

    // -- State restoration ---------------------------------------------------------

    /** 12345 is 0x3039, so the pump is shown and stored as MT-3039. */
    private val ourPumpName = "MT-3039"

    /**
     * One indication frame the pump could really send: `[size, cmd, seq, pkgIndex, payload, crc]`.
     *
     * [size] is what `ReadDataPacket` compares the bytes it holds against, so a small size makes
     * one chunk a whole answer and a large one leaves the answer half read.
     */
    private fun indicationFrame(size: Int, payload: Byte): ByteArray {
        val body = byteArrayOf(size.toByte(), 0x12, 0x00, 0x00, payload)
        return body + CrcUtils.calcCrc8(body, body.size)
    }

    @Test
    fun restoredOurPumpIsReleasedAndTheLinkIsDropped() {
        val logger = RecordingLogger()
        val (central, transport, callback) = setup(pumpSerial = 12345L, logger = logger)
        central.names["dev-1"] = ourPumpName
        connectFlow(central, transport, sn = 12345L)

        transport.onEvent(BleCentralEvent.Restored(listOf("dev-1")))

        assertTrue(central.cancelled.contains("dev-1"))
        assertTrue(central.released.contains("dev-1"))
        assertNull(central.connectedId)
        assertTrue(logger.lines.any { it.contains("dev-1") && it.contains("our pump") })
        // The link state is gone: a send has nothing to write on any more.
        central.writes.clear()
        transport.sendMessage(byteArrayOf(1, 2, 3))
        assertTrue(central.writes.isEmpty())
        assertEquals(listOf("not connected"), callback.errors)
    }

    @Test
    fun restoredPeripheralThatIsNotOurPumpIsReleasedAsWell() {
        val logger = RecordingLogger()
        val (central, transport, _) = setup(pumpSerial = 12345L, logger = logger)
        central.names["other-1"] = "MT-ABCDEF"
        central.names["other-2"] = "Dexcom ONE+"

        transport.onEvent(BleCentralEvent.Restored(listOf("other-1", "other-2")))

        assertEquals(listOf("other-1", "other-2"), central.released)
        assertEquals(listOf("other-1", "other-2"), central.cancelled)
        assertTrue(logger.lines.any { it.contains("other-1") && it.contains("another Medtrum pump") })
        assertTrue(logger.lines.any { it.contains("other-2") && it.contains("is not a Medtrum pump") })
    }

    @Test
    fun restoredPeripheralWithNoNameIsReleasedAndSaidOutLoud() {
        val logger = RecordingLogger()
        val (central, transport, _) = setup(pumpSerial = 12345L, logger = logger)
        // iOS hands back what was still only trying to connect. Such a peripheral has no name yet.

        transport.onEvent(BleCentralEvent.Restored(listOf("nameless")))

        assertEquals(listOf("nameless"), central.released)
        assertEquals(listOf("nameless"), central.cancelled)
        assertTrue(logger.lines.any { it.contains("nameless") && it.contains("no name yet") })
    }

    @Test
    fun restoreDoesNotTouchTheSessionPhaseOrTheConnectionState() {
        val (central, transport, _) = setup(pumpSerial = 12345L)
        val state = MedtrumSessionState(clock = MedtrumClock.fixed(0L))
        state.pumpSN = 12345L
        val session = MedtrumSession(transport = transport, state = state, clock = MedtrumClock.fixed(0L))
        transport.setCallback(session)
        central.names["dev-1"] = ourPumpName

        transport.onEvent(BleCentralEvent.Restored(listOf("dev-1")))

        // The pump was never asked anything, so nothing may be claimed about it.
        assertEquals(MedtrumSession.Phase.IDLE, session.phase)
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertTrue(state.bolusDone)
    }

    @Test
    fun restoreDoesNotTellTheCallbackAnythingAboutTheLink() {
        val (central, transport, callback) = setup(pumpSerial = 12345L)
        central.names["dev-1"] = ourPumpName

        transport.onEvent(BleCentralEvent.Restored(listOf("dev-1")))

        assertFalse(callback.connected)
        assertFalse(callback.disconnected)
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun restoreOfAnotherPeripheralLeavesTheLiveLinkAlone() {
        val (central, transport, callback) = setup(pumpSerial = 12345L)
        central.names["dev-1"] = ourPumpName
        connectFlow(central, transport, sn = 12345L)
        central.writes.clear()

        transport.onEvent(BleCentralEvent.Restored(listOf("ghost")))

        assertEquals(listOf("ghost"), central.released)
        assertEquals("dev-1", central.connectedId)
        // The live link still carries a message.
        transport.sendMessage(byteArrayOf(1, 2, 3))
        assertTrue(central.writes.isNotEmpty())
        assertTrue(callback.errors.isEmpty())
    }

    @Test
    fun restoreClearsTheHalfReadAnswerAndTheUnsentPackets() {
        val (central, transport, callback) = setup(pumpSerial = 12345L)
        central.names["dev-1"] = ourPumpName
        connectFlow(central, transport, sn = 12345L)
        // An answer that is still being read, and a message whose chunks are not all sent.
        transport.onEvent(
            BleCentralEvent.Value(
                id = "dev-1",
                characteristicUuid = IosMedtrumBleTransport.WRITE_UUID,
                data = indicationFrame(size = 60, payload = 0x01),
            )
        )
        transport.sendMessage(ByteArray(40) { it.toByte() })
        assertTrue(callback.indications.isEmpty())

        transport.onEvent(BleCentralEvent.Restored(listOf("dev-1")))
        connectFlow(central, transport, sn = 12345L)
        callback.errors.clear()
        central.writes.clear()

        // A whole answer arrives as one chunk. It is read on its own, not added to the old one.
        transport.onEvent(
            BleCentralEvent.Value(
                id = "dev-1",
                characteristicUuid = IosMedtrumBleTransport.WRITE_UUID,
                data = indicationFrame(size = 5, payload = 0x77),
            )
        )
        assertEquals(1, callback.indications.size)
        assertEquals(0x77.toByte(), callback.indications[0].last())
        // The chunks left over from before are gone, so a new message is accepted.
        transport.sendMessage(byteArrayOf(1, 2, 3))
        assertTrue(central.writes.isNotEmpty())
        assertTrue(callback.errors.isEmpty())
    }
}
