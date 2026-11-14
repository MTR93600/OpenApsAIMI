package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.scan.DashPodAdvertisement
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod

/**
 * One central of the phone, as CoreBluetooth behaves for the held connection, in front of the
 * simulated pod ([FakeDashPod]):
 * - [connect] has no timeout: when the pod is in range ([podInRange]) the link comes up at once
 *   (a [BleCentralEvent.Link] connected event); otherwise the connect is kept ([pendingConnect])
 *   until [podComesInRange];
 * - [cancel] drops a link that is up (the drop event comes, as `didDisconnectPeripheral`) and
 *   forgets a kept connect;
 * - [startScan] reports [advertisements] at once, except the one of the peripheral that is
 *   connected now (a connected pod does not advertise, and CoreBluetooth does not report it);
 * - the GATT calls go to the fake central of the current pod link ([FakeDashPod.central]).
 *
 * Every event goes to [listener] (set it to `DashHeldConnection.onEvent`), the GATT events of the
 * pod too: [rewire] sets the listener of the pod link, call it after [FakeDashPod.reconnect].
 */
internal class HeldPodCentral(private val pod: FakeDashPod) : BleCentral {

    var listener: (BleCentralEvent) -> Unit = {}
        set(value) {
            field = value
            rewire()
        }

    var adapter = BleAdapterState.PoweredOn

    /** True: a connect is answered at once. False: it is kept until [podComesInRange]. */
    var podInRange = true

    /** Each connect asked, as "id" or "id delay=N" for a start delay. */
    val connects = mutableListOf<String>()

    val cancelled = mutableListOf<String>()
    val released = mutableListOf<String>()

    /** The service filters of each scan. */
    val scans = mutableListOf<List<String>>()
    var scanStops = 0

    /** What a scan reports. */
    val advertisements = mutableListOf<BleCentralEvent.ScanResult>()

    /** A connect that iOS keeps open (the pod is out of range). */
    var pendingConnect: String? = null
        private set

    /** True while the BLE link is up. */
    var linkUp = false
        private set

    /** The peripheral of the link that is up, or of the last one. */
    private var linkedId: String? = null

    fun rewire() {
        pod.central.listener = listener
    }

    /** The pod comes in range: a kept connect comes up. */
    fun podComesInRange() {
        podInRange = true
        val id = pendingConnect ?: return
        pendingConnect = null
        linkUp = true
        linkedId = id
        listener(BleCentralEvent.Link(id, connected = true, status = 0))
    }

    /** The pod goes out of range: the link drops; the pod keeps its pairing for the next link. */
    fun podGoesAway() {
        podInRange = false
        dropLink()
    }

    /**
     * Bluetooth goes off, as on iOS: every link and kept connect is gone, and no drop event comes
     * for the peripheral, only the adapter state.
     */
    fun radioOff() {
        adapter = BleAdapterState.PoweredOff
        pendingConnect = null
        if (linkUp) {
            linkUp = false
            pod.reconnect()
            rewire()
        }
        listener(BleCentralEvent.AdapterState(BleAdapterState.PoweredOff))
    }

    fun radioOn() {
        adapter = BleAdapterState.PoweredOn
        listener(BleCentralEvent.AdapterState(BleAdapterState.PoweredOn))
    }

    private fun dropLink() {
        if (!linkUp) return
        linkUp = false
        pod.central.disconnect()
        pod.reconnect()
        rewire()
    }

    override fun adapterState(): BleAdapterState = adapter
    override fun startScan() = startScan(emptyList())

    override fun startScan(serviceUuids: List<String>) {
        scans += serviceUuids
        advertisements.toList().filter { !(linkUp && it.id == linkedId) }.forEach { listener(it) }
    }

    override fun stopScan() {
        scanStops++
    }

    override fun connect(id: String): Boolean = connect(id, 0)

    override fun connect(id: String, startDelaySeconds: Int): Boolean {
        connects += if (startDelaySeconds > 0) "$id delay=$startDelaySeconds" else id
        if (linkUp) {
            // CoreBluetooth answers a connect on a connected peripheral with a connect event.
            listener(BleCentralEvent.Link(id, connected = true, status = 0))
            return true
        }
        if (podInRange && startDelaySeconds == 0) {
            linkUp = true
            linkedId = id
            listener(BleCentralEvent.Link(id, connected = true, status = 0))
        } else {
            pendingConnect = id
        }
        return true
    }

    override fun cancel(id: String) {
        cancelled += id
        pendingConnect = null
        dropLink()
    }

    override fun release(id: String) {
        released += id
    }

    override fun discoverServices(id: String) = pod.central.discoverServices(id)
    override fun discoverCharacteristics(id: String, serviceUuid: String) = pod.central.discoverCharacteristics(id, serviceUuid)
    override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean =
        pod.central.write(id, characteristicUuid, data, withResponse)

    override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean =
        pod.central.enableNotify(id, characteristicUuid, indicate)

    override fun cachedName(id: String): String? = null

    companion object {

        const val POD_ID = FakeDashPodCentral.POD_ID

        /**
         * The 9 service UUIDs of a pod advertisement, built from the rules of Android
         * `BleDiscoveredDevice` (not a recorded advertisement): 4024, 2470, 000a, the pod id in two
         * parts, then lot and sequence (lot 0x0812345678 = 34668910200, sequence 0x09abcd = 633805).
         */
        fun podServiceUuids(podId: Long = DashPodAdvertisement.POD_ID_NOT_ACTIVATED, first: String = "4024", third: String = "000a"): List<String> {
            val id = podId.toString(16).padStart(8, '0')
            return listOf(first, "2470", third, id.substring(0, 4), id.substring(4, 8), "0812", "3456", "7809", "abcd")
                .map { "0000$it-0000-1000-8000-00805f9b34fb" }
        }

        fun advertisement(id: String, serviceUuids: List<String> = podServiceUuids()) =
            BleCentralEvent.ScanResult(name = null, id = id, advertisementBytes = null, serviceUuids = serviceUuids)
    }
}
