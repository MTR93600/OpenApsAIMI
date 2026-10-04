package app.aaps.core.interfaces.pump.ble.session

import app.aaps.core.interfaces.pump.ble.BleTransportListener
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract Dana, Medtrum and Equil already share, exercised against a fake central.
 *
 * Callbacks that the Android GATT thread delivers are delivered inside [BleSession.onEvent], before
 * it returns. A write without response has no such callback on CoreBluetooth, so its
 * [BleTransportListener.onCharacteristicWritten] is handed to the poster and must not run inside
 * [BleSession.write].
 */
class BleSessionTest {

    private val notifyUuid = "0000fff1-0000-1000-8000-00805f9b34fb"
    private val writeUuid = "0000fff2-0000-1000-8000-00805f9b34fb"
    private val serviceUuid = "0000fff0-0000-1000-8000-00805f9b34fb"

    @Test
    fun `a zero stack status is not a failure`() {
        assertNull(bleStackFailure("write", 0, null))
    }

    @Test
    fun `a non zero stack status is kept`() {
        val failure = bleStackFailure("write", 133, "gatt")
        val stack = assertIs<BleFailure.Stack>(failure)
        assertEquals("write", stack.operation)
        assertEquals(133, stack.status)
        assertEquals("gatt", stack.message)
    }

    @Test
    fun `scan emits a named device and keeps the advertisement bytes`() {
        val harness = harness()
        val seen = harness.collectScans()
        harness.session.startScan()
        harness.session.onEvent(BleCentralEvent.ScanResult("Dana", "AA", byteArrayOf(1, 2, 3)))
        assertEquals(listOf("startScan"), harness.central.calls)
        assertEquals(1, seen.size)
        assertEquals("Dana", seen[0].name)
        assertEquals("AA", seen[0].address)
        assertContentEquals(byteArrayOf(1, 2, 3), seen[0].scanRecordBytes)
        seen.job.cancel()
    }

    @Test
    fun `scan drops an empty name`() {
        val harness = harness()
        val seen = harness.collectScans()
        harness.session.startScan()
        harness.session.onEvent(BleCentralEvent.ScanResult("", "AA", null))
        harness.session.onEvent(BleCentralEvent.ScanResult(null, "BB", null))
        assertTrue(seen.isEmpty())
        seen.job.cancel()
    }

    @Test
    fun `scan while powered off records the adapter and does not start`() {
        val harness = harness(state = BleAdapterState.PoweredOff)
        harness.session.startScan()
        assertTrue(harness.central.calls.isEmpty())
        assertIs<BleFailure.AdapterNotReady>(harness.session.failures.single())
    }

    @Test
    fun `connect while powered off returns false and stays silent`() {
        val harness = harness(state = BleAdapterState.Unauthorized)
        assertFalse(harness.session.connect("AA"))
        assertTrue(harness.listener.connections.isEmpty())
        assertTrue(harness.central.calls.isEmpty())
        assertIs<BleFailure.AdapterNotReady>(harness.session.failures.single())
    }

    @Test
    fun `connect returns before any link callback`() {
        val harness = harness()
        assertTrue(harness.session.connect("AA"))
        assertEquals(listOf("connect:AA"), harness.central.calls)
        assertTrue(harness.listener.connections.isEmpty())
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        assertEquals(listOf(true), harness.listener.connections)
    }

    @Test
    fun `discovery failure is false and characteristics stay missing`() {
        val harness = harness()
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        harness.session.discoverServices()
        harness.session.onEvent(BleCentralEvent.Services("AA", emptyList(), status = 257))
        assertEquals(listOf(false), harness.listener.services)
        assertFalse(harness.session.findCharacteristics())
        assertIs<BleFailure.Stack>(harness.session.failures.single())
    }

    @Test
    fun `notifications enabled before characteristics exist do not write a descriptor`() {
        val harness = harness()
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        harness.session.enableNotifications()
        assertTrue(harness.central.calls.none { it.startsWith("notify") })
        assertEquals(0, harness.listener.descriptors)
    }

    @Test
    fun `the listener hears connect then services then descriptor then write`() {
        val harness = harness()
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        harness.session.discoverServices()
        harness.session.onEvent(BleCentralEvent.Services("AA", uartServices(), status = 0))
        assertEquals(listOf(true), harness.listener.services)
        assertTrue(harness.session.findCharacteristics())
        harness.session.enableNotifications()
        assertEquals(listOf("notify:false"), harness.central.calls.filter { it.startsWith("notify") })
        assertEquals(0, harness.listener.descriptors)
        harness.session.onEvent(BleCentralEvent.DescriptorWritten("AA", profile().clientConfigDescriptorUuid, status = 0))
        assertEquals(1, harness.listener.descriptors)
        harness.session.write(byteArrayOf(9))
        assertEquals(0, harness.listener.writes)
        harness.posted.single().invoke()
        assertEquals(1, harness.listener.writes)
        assertEquals(listOf(true), harness.listener.connections)
    }

    @Test
    fun `a failed descriptor write still notifies and keeps the status`() {
        val harness = harness()
        ready(harness)
        harness.session.enableNotifications()
        harness.session.onEvent(BleCentralEvent.DescriptorWritten("AA", "cccd", status = 5))
        assertEquals(1, harness.listener.descriptors)
        val stack = assertIs<BleFailure.Stack>(harness.session.failures.single())
        assertEquals(5, stack.status)
    }

    @Test
    fun `a failed write with response still notifies and keeps the status`() {
        val harness = harness(writeMode = WriteMode.WithResponse)
        ready(harness)
        harness.session.write(byteArrayOf(1))
        assertEquals(0, harness.listener.writes)
        assertTrue(harness.posted.isEmpty())
        harness.session.onEvent(BleCentralEvent.Written("AA", writeUuid, status = 7))
        assertEquals(1, harness.listener.writes)
        assertEquals(7, assertIs<BleFailure.Stack>(harness.session.failures.single()).status)
        assertEquals(listOf("write:true:1"), harness.central.calls.filter { it.startsWith("write") })
    }

    @Test
    fun `android default writes without response when that property is present`() {
        val harness = harness()
        ready(harness)
        harness.session.write(byteArrayOf(1, 2))
        assertEquals(listOf("write:false:2"), harness.central.calls.filter { it.startsWith("write") })
    }

    @Test
    fun `a profile can force a write with response`() {
        val harness = harness(writeMode = WriteMode.WithResponse)
        ready(harness)
        harness.session.write(byteArrayOf(4))
        assertEquals(listOf("write:true:1"), harness.central.calls.filter { it.startsWith("write") })
    }

    @Test
    fun `disconnect reports false and close does not report again`() {
        val harness = harness()
        ready(harness)
        harness.session.disconnect()
        assertEquals(listOf("cancel:AA"), harness.central.calls.filter { it.startsWith("cancel") })
        assertTrue(harness.listener.connections == listOf(true))
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = false, status = 0))
        assertEquals(listOf(true, false), harness.listener.connections)
        harness.session.close()
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = false, status = 0))
        assertEquals(listOf(true, false), harness.listener.connections)
        assertTrue(harness.central.calls.any { it == "release:AA" })
    }

    @Test
    fun `a notification delivers the same bytes`() {
        val harness = harness()
        ready(harness)
        val payload = byteArrayOf(8, 9)
        harness.session.onEvent(BleCentralEvent.Value("AA", notifyUuid, payload))
        assertEquals(1, harness.listener.values.size)
        assertContentEquals(payload, harness.listener.values.single())
    }

    @Test
    fun `a connect timeout fires at the deadline and not before`() {
        val harness = harness(timeoutMillis = 1_000)
        assertTrue(harness.session.connect("AA"))
        harness.session.poll(999)
        assertTrue(harness.listener.connections.isEmpty())
        harness.session.poll(1_000)
        assertEquals(listOf(false), harness.listener.connections)
        assertIs<BleFailure.Timeout>(harness.session.failures.single())
    }

    @Test
    fun `polling does nothing when no timeout is configured`() {
        val harness = harness(timeoutMillis = null)
        assertTrue(harness.session.connect("AA"))
        harness.session.poll(10_000)
        assertTrue(harness.listener.connections.isEmpty())
        assertTrue(harness.session.failures.isEmpty())
    }

    @Test
    fun `a discover timeout reports services false`() {
        val harness = harness(timeoutMillis = 50)
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        harness.session.poll(0)
        harness.session.discoverServices()
        harness.session.poll(49)
        assertTrue(harness.listener.services.isEmpty())
        harness.session.poll(50)
        assertEquals(listOf(false), harness.listener.services)
        assertTrue(harness.listener.connections == listOf(true))
    }

    @Test
    fun `a write timeout does not look like a completed write`() {
        val harness = harness(timeoutMillis = 20, writeMode = WriteMode.WithResponse)
        ready(harness)
        harness.session.poll(0)
        harness.session.write(byteArrayOf(1))
        harness.session.poll(20)
        assertEquals(0, harness.listener.writes)
        assertEquals(listOf(true, false), harness.listener.connections)
        assertIs<BleFailure.Timeout>(harness.session.failures.single())
    }

    @Test
    fun `bond methods fail visibly`() {
        val harness = harness()
        assertFalse(harness.session.isBonded("AA"))
        assertFalse(harness.session.createBond("AA"))
        harness.session.removeBond("AA")
        assertEquals(2, harness.session.failures.count { it is BleFailure.BondUnsupported })
    }

    @Test
    fun `the radio dropping during a scan is recorded`() {
        val harness = harness()
        harness.session.startScan()
        harness.session.onEvent(BleCentralEvent.AdapterState(BleAdapterState.PoweredOff))
        assertIs<BleFailure.AdapterNotReady>(harness.session.failures.single())
    }

    @Test
    fun `a scan failure from the central is recorded`() {
        val harness = harness()
        harness.session.startScan()
        harness.session.onEvent(BleCentralEvent.ScanFailed("powered off"))
        val rejected = assertIs<BleFailure.CentralRejected>(harness.session.failures.single())
        assertEquals("scan", rejected.operation)
        assertEquals("powered off", rejected.message)
    }

    @Test
    fun `a failed link reports disconnected`() {
        val harness = harness()
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = false, status = 133))
        assertEquals(listOf(false), harness.listener.connections)
        assertEquals(133, assertIs<BleFailure.Stack>(harness.session.failures.single()).status)
    }

    @Test
    fun `the scanned name is what getDeviceName returns`() {
        val harness = harness()
        harness.session.onEvent(BleCentralEvent.ScanResult("Equil", "ID-1", null))
        assertEquals("Equil", harness.session.deviceName("ID-1"))
        assertNull(harness.session.deviceName("missing"))
    }

    @Test
    fun `two advertisements are both emitted`() {
        val harness = harness()
        val seen = harness.collectScans()
        harness.session.onEvent(BleCentralEvent.ScanResult("MT", "1", null))
        harness.session.onEvent(BleCentralEvent.ScanResult("MT", "1", null))
        assertEquals(2, seen.size)
        seen.job.cancel()
    }

    @Test
    fun `stopScan reaches the central`() {
        val harness = harness()
        harness.session.startScan()
        harness.session.stopScan()
        assertEquals(listOf("startScan", "stopScan"), harness.central.calls)
    }

    @Test
    fun `characteristic lookup ignores uuid case`() {
        val harness = harness()
        ready(harness, notify = notifyUuid.uppercase(), write = writeUuid.uppercase())
        assertTrue(harness.session.findCharacteristics())
    }

    @Test
    fun `an indicate-only characteristic asks for indications`() {
        val harness = harness()
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        harness.session.onEvent(
            BleCentralEvent.Services(
                "AA",
                listOf(
                    BleGattService(
                        serviceUuid,
                        listOf(
                            BleGattCharacteristic(notifyUuid, setOf(BleCharProperty.Indicate)),
                            BleGattCharacteristic(writeUuid, setOf(BleCharProperty.Write))
                        )
                    )
                ),
                status = 0
            )
        )
        assertTrue(harness.session.findCharacteristics())
        harness.session.enableNotifications()
        assertEquals(listOf("notify:true"), harness.central.calls.filter { it.startsWith("notify") })
    }

    @Test
    fun `empty characteristic lists are filled before services are reported`() {
        val harness = harness()
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        harness.session.discoverServices()
        harness.session.onEvent(
            BleCentralEvent.Services(
                "AA",
                listOf(BleGattService(serviceUuid, emptyList())),
                status = 0
            )
        )
        assertTrue(harness.listener.services.isEmpty())
        assertEquals(listOf("chars:$serviceUuid"), harness.central.calls.filter { it.startsWith("chars") })
        harness.session.onEvent(
            BleCentralEvent.Characteristics(
                "AA",
                serviceUuid,
                listOf(
                    BleGattCharacteristic(notifyUuid, setOf(BleCharProperty.Notify)),
                    BleGattCharacteristic(writeUuid, setOf(BleCharProperty.WriteNoResponse))
                ),
                status = 0
            )
        )
        assertEquals(listOf(true), harness.listener.services)
        assertTrue(harness.session.findCharacteristics())
    }

    @Test
    fun `connection priority is not forwarded`() {
        val harness = harness()
        harness.session.requestConnectionPriority(1)
        assertTrue(harness.central.calls.isEmpty())
        assertTrue(harness.session.failures.isEmpty())
    }

    @Test
    fun `a write while disconnected is recorded and does not callback`() {
        val harness = harness()
        harness.session.write(byteArrayOf(1))
        assertEquals(0, harness.listener.writes)
        assertTrue(harness.listener.connections.isEmpty())
        assertIs<BleFailure.NotConnected>(harness.session.failures.single())
        assertTrue(harness.central.calls.none { it.startsWith("write") })
    }

    @Test
    fun `discover while disconnected does not callback`() {
        val harness = harness()
        harness.session.discoverServices()
        assertTrue(harness.listener.services.isEmpty())
        assertTrue(harness.central.calls.none { it.startsWith("discover") })
        assertIs<BleFailure.NotConnected>(harness.session.failures.single())
    }

    @Test
    fun `enable while the radio is off is recorded`() {
        val harness = harness(state = BleAdapterState.PoweredOff)
        harness.session.enable()
        assertIs<BleFailure.AdapterNotReady>(harness.session.failures.single())
    }

    private fun profile(writeMode: WriteMode = WriteMode.AndroidDefault) = BleProfile(
        notifyCharacteristicUuid = notifyUuid,
        writeCharacteristicUuid = writeUuid,
        serviceUuid = null,
        writeMode = writeMode
    )

    private fun uartServices(
        notify: String = notifyUuid,
        write: String = writeUuid
    ) = listOf(
        BleGattService(
            serviceUuid,
            listOf(
                BleGattCharacteristic(notify, setOf(BleCharProperty.Notify, BleCharProperty.Read)),
                BleGattCharacteristic(write, setOf(BleCharProperty.Write, BleCharProperty.WriteNoResponse))
            )
        )
    )

    private fun harness(
        state: BleAdapterState = BleAdapterState.PoweredOn,
        timeoutMillis: Long? = null,
        writeMode: WriteMode = WriteMode.AndroidDefault
    ): Harness {
        val central = FakeCentral(state)
        val posted = ArrayDeque<() -> Unit>()
        val session = BleSession(
            central,
            profile(writeMode),
            timeoutMillis,
            post = { block -> posted.addLast(block) }
        )
        val listener = RecordingListener()
        session.setListener(listener)
        return Harness(central, session, listener, posted)
    }

    private fun ready(harness: Harness, notify: String = notifyUuid, write: String = writeUuid) {
        harness.session.connect("AA")
        harness.session.onEvent(BleCentralEvent.Link("AA", connected = true, status = 0))
        harness.session.discoverServices()
        harness.session.onEvent(BleCentralEvent.Services("AA", uartServices(notify, write), status = 0))
        assertTrue(harness.session.findCharacteristics())
    }

    private class Harness(
        val central: FakeCentral,
        val session: BleSession,
        val listener: RecordingListener,
        val posted: ArrayDeque<() -> Unit>
    ) {
        fun collectScans(): ScanBag {
            val bag = ScanBag()
            bag.job = CoroutineScope(Dispatchers.Unconfined).launch {
                session.scannedDevices.collect { bag.items += it }
            }
            return bag
        }
    }

    private class ScanBag {
        val items = mutableListOf<ScannedDevice>()
        lateinit var job: Job
        val size: Int get() = items.size
        operator fun get(index: Int): ScannedDevice = items[index]
        fun isEmpty(): Boolean = items.isEmpty()
    }

    private class RecordingListener : BleTransportListener {
        val connections = mutableListOf<Boolean>()
        val services = mutableListOf<Boolean>()
        var descriptors: Int = 0
        val values = mutableListOf<ByteArray>()
        var writes: Int = 0

        override fun onConnectionStateChanged(connected: Boolean) {
            connections += connected
        }

        override fun onServicesDiscovered(success: Boolean) {
            services += success
        }

        override fun onDescriptorWritten() {
            descriptors += 1
        }

        override fun onCharacteristicChanged(data: ByteArray) {
            values += data
        }

        override fun onCharacteristicWritten() {
            writes += 1
        }
    }

    private class FakeCentral(var state: BleAdapterState) : BleCentral {
        val calls = mutableListOf<String>()
        override fun adapterState(): BleAdapterState = state
        override fun startScan() {
            calls += "startScan"
        }

        override fun stopScan() {
            calls += "stopScan"
        }

        override fun connect(id: String): Boolean {
            calls += "connect:$id"
            return true
        }

        override fun cancel(id: String) {
            calls += "cancel:$id"
        }

        override fun release(id: String) {
            calls += "release:$id"
        }

        override fun discoverServices(id: String) {
            calls += "discover:$id"
        }

        override fun discoverCharacteristics(id: String, serviceUuid: String) {
            calls += "chars:$serviceUuid"
        }

        override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean {
            calls += "write:$withResponse:${data.size}"
            return true
        }

        override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean {
            calls += "notify:$indicate"
            return true
        }

        override fun cachedName(id: String): String? = null
    }
}
