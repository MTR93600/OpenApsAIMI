package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral

/**
 * A scripted [BleCentral], the reason the central sits behind an interface at all.
 *
 * Every call records what was asked for and then runs the matching hook, so a test can answer a
 * request the way CoreBluetooth would - synchronously, before the caller starts waiting. That is
 * what lets the blocking driver code be tested without a second thread.
 */
class FakeBleCentral : BleCentral {

    var adapter: BleAdapterState = BleAdapterState.PoweredOn
    var connectAccepted: Boolean = true
    var writeAccepted: Boolean = true
    var notifyAccepted: Boolean = true

    var scanning: Boolean = false
        private set

    var scanStarts: Int = 0
        private set

    val connects = mutableListOf<String>()
    val cancels = mutableListOf<String>()
    val writes = mutableListOf<Write>()
    val notifies = mutableListOf<Subscribe>()

    var onStartScan: (() -> Unit)? = null
    var onConnect: ((String) -> Unit)? = null
    var onDiscoverServices: ((String) -> Unit)? = null
    var onDiscoverCharacteristics: ((String, String) -> Unit)? = null
    var onWrite: ((Write) -> Unit)? = null
    var onEnableNotify: ((Subscribe) -> Unit)? = null

    data class Write(val id: String, val characteristicUuid: String, val data: ByteArray, val withResponse: Boolean) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Write) return false
            return id == other.id &&
                characteristicUuid == other.characteristicUuid &&
                data.contentEquals(other.data) &&
                withResponse == other.withResponse
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + characteristicUuid.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + withResponse.hashCode()
            return result
        }
    }

    data class Subscribe(val id: String, val characteristicUuid: String, val indicate: Boolean)

    override fun adapterState(): BleAdapterState = adapter

    override fun startScan() {
        lastScanFilter = emptyList()
        scanning = true
        scanStarts++
        onStartScan?.invoke()
    }

    /** The service filter of the last scan; empty for a scan with no filter. */
    var lastScanFilter: List<String> = emptyList()

    override fun startScan(serviceUuids: List<String>) {
        startScan()
        lastScanFilter = serviceUuids
    }

    override fun stopScan() {
        scanning = false
    }

    override fun connect(id: String): Boolean {
        if (!connectAccepted) return false
        connects += id
        onConnect?.invoke(id)
        return true
    }

    override fun cancel(id: String) {
        cancels += id
    }

    override fun release(id: String) {
        cancels += id
    }

    override fun discoverServices(id: String) {
        onDiscoverServices?.invoke(id)
    }

    override fun discoverCharacteristics(id: String, serviceUuid: String) {
        onDiscoverCharacteristics?.invoke(id, serviceUuid)
    }

    override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean {
        if (!writeAccepted) return false
        val write = Write(id, characteristicUuid, data, withResponse)
        writes += write
        onWrite?.invoke(write)
        return true
    }

    override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean {
        if (!notifyAccepted) return false
        val subscribe = Subscribe(id, characteristicUuid, indicate)
        notifies += subscribe
        onEnableNotify?.invoke(subscribe)
        return true
    }

    override fun cachedName(id: String): String? = null
}
