package app.aaps.core.interfaces.pump.ble.session

import app.aaps.core.interfaces.pump.ble.BleTransportListener
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What the platform radio is doing. Names follow `CBManagerState` and the Android adapter states
 * the three pump transports already branch on (radio on, radio off).
 */
enum class BleAdapterState {
    Unknown,
    Resetting,
    Unsupported,
    Unauthorized,
    PoweredOff,
    PoweredOn
}

enum class BleCharProperty {
    Read,
    Write,
    WriteNoResponse,
    Notify,
    Indicate
}

data class BleGattCharacteristic(
    val uuid: String,
    val properties: Set<BleCharProperty>
)

data class BleGattService(
    val uuid: String,
    val characteristics: List<BleGattCharacteristic>
)

/**
 * How [BleSession] chooses an ATT write.
 *
 * [AndroidDefault] is the `BluetoothGattCharacteristic` constructor: `WRITE_NO_RESPONSE` when that
 * property is present, otherwise a write with response. Dana forces [NoResponse]. Medtrum forces
 * [WithResponse]. The shared [app.aaps.core.interfaces.pump.ble.BleTransport] contract has no
 * parameter for this, so it stays on the implementation.
 */
enum class WriteMode {
    AndroidDefault,
    WithResponse,
    NoResponse
}

data class BleProfile(
    val notifyCharacteristicUuid: String,
    val writeCharacteristicUuid: String,
    val serviceUuid: String? = null,
    val writeMode: WriteMode = WriteMode.AndroidDefault,
    val clientConfigDescriptorUuid: String = "00002902-0000-1000-8000-00805f9b34fb"
)

sealed class BleFailure {
    data class AdapterNotReady(val state: BleAdapterState) : BleFailure()
    data object BondUnsupported : BleFailure()
    data object NotConnected : BleFailure()
    data class Stack(val operation: String, val status: Int, val message: String?) : BleFailure()
    data class Timeout(val operation: String) : BleFailure()
    data object ScanBufferFull : BleFailure()
    data class CentralRejected(val operation: String, val message: String) : BleFailure()
}

/** A non-zero ATT / GATT status is a failure. Zero is success and is not recorded. */
fun bleStackFailure(operation: String, status: Int, message: String?): BleFailure? =
    if (status == 0) null else BleFailure.Stack(operation, status, message)

sealed class BleCentralEvent {
    data class AdapterState(val state: BleAdapterState) : BleCentralEvent()
    data class ScanResult(val name: String?, val id: String, val advertisementBytes: ByteArray?) : BleCentralEvent()
    data class ScanFailed(val message: String) : BleCentralEvent()
    data class OperationFailed(val operation: String, val message: String) : BleCentralEvent()
    data class Link(val id: String, val connected: Boolean, val status: Int) : BleCentralEvent()
    data class Services(val id: String, val services: List<BleGattService>, val status: Int) : BleCentralEvent()
    data class Characteristics(
        val id: String,
        val serviceUuid: String,
        val characteristics: List<BleGattCharacteristic>,
        val status: Int
    ) : BleCentralEvent()
    data class Written(val id: String, val characteristicUuid: String, val status: Int) : BleCentralEvent()
    data class Value(val id: String, val characteristicUuid: String, val data: ByteArray) : BleCentralEvent()
    data class DescriptorWritten(val id: String, val descriptorUuid: String, val status: Int) : BleCentralEvent()
}

/**
 * The slice of CoreBluetooth (or a fake) the session is allowed to call.
 *
 * Bonding is absent on purpose: CoreBluetooth has no `createBond`.
 */
interface BleCentral {
    fun adapterState(): BleAdapterState
    fun startScan()
    fun stopScan()
    fun connect(id: String): Boolean
    fun cancel(id: String)
    fun release(id: String)
    fun discoverServices(id: String)
    fun discoverCharacteristics(id: String, serviceUuid: String)
    fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean
    fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean
    fun cachedName(id: String): String?
}

/**
 * Listener order, errors and timeouts for one [app.aaps.core.interfaces.pump.ble.BleTransport].
 *
 * Dana's [app.aaps.core.interfaces.pump.ble.BleTransportListener] is invoked from the GATT callback,
 * on that callback's thread, before the callback returns. [onEvent] does the same. A write without
 * response has no callback on CoreBluetooth, so its completion is given to [post] and does not run
 * inside [write]. [timeoutMillis] null matches the Android transports, which do not time out inside
 * `BleTransport`.
 *
 * `createBond` cannot be started from CoreBluetooth. The methods return false and record
 * [BleFailure.BondUnsupported]. They do not pretend a bond exists: Dana's `BLEComm.connect` refuses
 * to go on when [isBonded] is false, and this lot does not change that pump.
 */
class BleSession(
    private val central: BleCentral,
    val profile: BleProfile,
    val timeoutMillis: Long? = null,
    private val post: ((() -> Unit) -> Unit)? = null,
    /**
     * Time base for [timeoutMillis]. Tests leave this null and drive [poll] themselves, so a deadline
     * is `lastNow + budget` on that synthetic clock. An iOS transport that sets a timeout passes the
     * wall clock and calls [poll] with the same clock.
     */
    private val clock: (() -> Long)? = null
) {
    private val recorded = mutableListOf<BleFailure>()
    val failures: List<BleFailure> get() = recorded

    private val _scanned = MutableSharedFlow<ScannedDevice>(extraBufferCapacity = 64)
    val scannedDevices: SharedFlow<ScannedDevice> = _scanned

    private var listener: BleTransportListener? = null
    private var linkId: String? = null
    private var connected = false
    private var scanning = false
    private var ignoreNextDisconnect = false
    private val names = mutableMapOf<String, String>()
    private val services = mutableListOf<BleGattService>()
    private var pendingCharacteristicServices: MutableList<String>? = null
    private var notifyChar: BleGattCharacteristic? = null
    private var writeChar: BleGattCharacteristic? = null
    private var deadlineAt: Long? = null
    private var deadlineOp: String? = null
    private var lastNow: Long = 0L

    fun setListener(listener: BleTransportListener?) {
        this.listener = listener
    }

    fun enable() {
        val state = central.adapterState()
        if (state != BleAdapterState.PoweredOn) recorded += BleFailure.AdapterNotReady(state)
    }

    fun deviceName(id: String): String? = names[id] ?: central.cachedName(id)

    /** CoreBluetooth does not expose an Android bond table. [id] is accepted and ignored. */
    @Suppress("UNUSED_PARAMETER")
    fun isBonded(id: String): Boolean = false

    fun createBond(id: String): Boolean {
        recorded += BleFailure.BondUnsupported
        return false
    }

    fun removeBond(id: String) {
        recorded += BleFailure.BondUnsupported
    }

    fun startScan() {
        val state = central.adapterState()
        if (state != BleAdapterState.PoweredOn) {
            recorded += BleFailure.AdapterNotReady(state)
            return
        }
        scanning = true
        central.startScan()
    }

    fun stopScan() {
        scanning = false
        central.stopScan()
    }

    fun connect(id: String): Boolean {
        val state = central.adapterState()
        if (state != BleAdapterState.PoweredOn) {
            recorded += BleFailure.AdapterNotReady(state)
            return false
        }
        if (!central.connect(id)) {
            recorded += BleFailure.CentralRejected("connect", id)
            return false
        }
        linkId = id
        connected = false
        ignoreNextDisconnect = false
        notifyChar = null
        writeChar = null
        services.clear()
        pendingCharacteristicServices = null
        arm("connect")
        return true
    }

    fun disconnect() {
        val id = linkId ?: return
        central.cancel(id)
    }

    fun close() {
        val id = linkId ?: return
        ignoreNextDisconnect = true
        central.release(id)
        linkId = null
        connected = false
        notifyChar = null
        writeChar = null
        services.clear()
        pendingCharacteristicServices = null
        clearDeadline()
    }

    fun discoverServices() {
        val id = linkId
        if (id == null || !connected) {
            recorded += BleFailure.NotConnected
            return
        }
        central.discoverServices(id)
        arm("discover")
    }

    fun findCharacteristics(): Boolean {
        val pool = profile.serviceUuid?.let { wanted ->
            services.filter { sameUuid(it.uuid, wanted) }
        } ?: services
        val all = pool.flatMap { it.characteristics }
        notifyChar = all.firstOrNull { sameUuid(it.uuid, profile.notifyCharacteristicUuid) }
        writeChar = all.firstOrNull { sameUuid(it.uuid, profile.writeCharacteristicUuid) }
        return notifyChar != null && writeChar != null
    }

    fun enableNotifications() {
        val id = linkId
        val characteristic = notifyChar
        if (id == null || !connected || characteristic == null) return
        val indicate = BleCharProperty.Notify !in characteristic.properties &&
            BleCharProperty.Indicate in characteristic.properties
        if (!central.enableNotify(id, characteristic.uuid, indicate)) {
            recorded += BleFailure.CentralRejected("notify", characteristic.uuid)
            return
        }
        arm("descriptor")
    }

    fun write(data: ByteArray) {
        val id = linkId
        val characteristic = writeChar
        if (id == null || !connected || characteristic == null) {
            recorded += BleFailure.NotConnected
            return
        }
        val withResponse = writeWithResponse(profile.writeMode, characteristic.properties)
        if (!central.write(id, characteristic.uuid, data, withResponse)) {
            recorded += BleFailure.CentralRejected("write", characteristic.uuid)
            return
        }
        if (withResponse) {
            arm("write")
        } else {
            val fire = {
                listener?.onCharacteristicWritten()
                Unit
            }
            val poster = post
            if (poster != null) poster.invoke(fire) else fire()
        }
    }

    /** The interface default is empty. CoreBluetooth has no connection-priority request. */
    @Suppress("UNUSED_PARAMETER")
    fun requestConnectionPriority(priority: Int) = Unit

    fun onEvent(event: BleCentralEvent) {
        when (event) {
            is BleCentralEvent.AdapterState -> onAdapter(event.state)
            is BleCentralEvent.ScanResult -> onScan(event)
            is BleCentralEvent.ScanFailed -> recorded += BleFailure.CentralRejected("scan", event.message)
            is BleCentralEvent.OperationFailed -> recorded += BleFailure.CentralRejected(event.operation, event.message)
            is BleCentralEvent.Link -> onLink(event)
            is BleCentralEvent.Services -> onServices(event)
            is BleCentralEvent.Characteristics -> onCharacteristics(event)
            is BleCentralEvent.Written -> onWritten(event)
            is BleCentralEvent.Value -> listener?.onCharacteristicChanged(event.data.copyOf())
            is BleCentralEvent.DescriptorWritten -> onDescriptor(event)
        }
    }

    fun poll(nowMs: Long) {
        lastNow = nowMs
        val due = deadlineAt ?: return
        if (nowMs < due) return
        val operation = deadlineOp ?: return
        clearDeadline()
        recorded += BleFailure.Timeout(operation)
        when (operation) {
            "connect" -> {
                connected = false
                listener?.onConnectionStateChanged(false)
            }
            "discover" -> listener?.onServicesDiscovered(false)
            "write", "descriptor" -> {
                connected = false
                listener?.onConnectionStateChanged(false)
            }
        }
    }

    private fun onAdapter(state: BleAdapterState) {
        if (scanning && state != BleAdapterState.PoweredOn) {
            recorded += BleFailure.AdapterNotReady(state)
        }
    }

    private fun onScan(event: BleCentralEvent.ScanResult) {
        val name = event.name
        if (!name.isNullOrEmpty()) names[event.id] = name
        if (name.isNullOrEmpty()) return
        val emitted = _scanned.tryEmit(
            ScannedDevice(name = name, address = event.id, scanRecordBytes = event.advertisementBytes?.copyOf())
        )
        if (!emitted) recorded += BleFailure.ScanBufferFull
    }

    private fun onLink(event: BleCentralEvent.Link) {
        if (event.id != linkId) {
            if (ignoreNextDisconnect && !event.connected) ignoreNextDisconnect = false
            return
        }
        if (!event.connected && ignoreNextDisconnect) {
            ignoreNextDisconnect = false
            return
        }
        bleStackFailure("link", event.status, null)?.let { recorded += it }
        if (event.connected) {
            connected = true
            clearDeadline()
            listener?.onConnectionStateChanged(true)
        } else {
            connected = false
            clearDeadline()
            listener?.onConnectionStateChanged(false)
        }
    }

    private fun onServices(event: BleCentralEvent.Services) {
        if (event.id != linkId) return
        val failure = bleStackFailure("discover", event.status, null)
        if (failure != null) {
            recorded += failure
            clearDeadline()
            pendingCharacteristicServices = null
            listener?.onServicesDiscovered(false)
            return
        }
        val missing = event.services.filter { it.characteristics.isEmpty() }
        if (missing.isEmpty()) {
            services.clear()
            services += event.services
            clearDeadline()
            listener?.onServicesDiscovered(true)
            return
        }
        services.clear()
        services += event.services
        pendingCharacteristicServices = missing.map { it.uuid }.toMutableList()
        central.discoverCharacteristics(event.id, missing.first().uuid)
    }

    private fun onCharacteristics(event: BleCentralEvent.Characteristics) {
        if (event.id != linkId) return
        val failure = bleStackFailure("characteristics", event.status, null)
        if (failure != null) {
            recorded += failure
            pendingCharacteristicServices = null
            clearDeadline()
            listener?.onServicesDiscovered(false)
            return
        }
        val index = services.indexOfFirst { sameUuid(it.uuid, event.serviceUuid) }
        if (index >= 0) {
            services[index] = services[index].copy(characteristics = event.characteristics)
        }
        val pending = pendingCharacteristicServices
        pending?.removeAll { sameUuid(it, event.serviceUuid) }
        val next = pending?.firstOrNull()
        if (next == null) {
            pendingCharacteristicServices = null
            clearDeadline()
            listener?.onServicesDiscovered(true)
        } else {
            central.discoverCharacteristics(event.id, next)
        }
    }

    private fun onWritten(event: BleCentralEvent.Written) {
        if (event.id != linkId) return
        bleStackFailure("write", event.status, null)?.let { recorded += it }
        clearDeadline()
        listener?.onCharacteristicWritten()
    }

    private fun onDescriptor(event: BleCentralEvent.DescriptorWritten) {
        if (event.id != linkId) return
        bleStackFailure("descriptor", event.status, null)?.let { recorded += it }
        clearDeadline()
        listener?.onDescriptorWritten()
    }

    private fun arm(operation: String) {
        val budget = timeoutMillis ?: return
        deadlineAt = (clock?.invoke() ?: lastNow) + budget
        deadlineOp = operation
    }

    private fun clearDeadline() {
        deadlineAt = null
        deadlineOp = null
    }
}

internal fun sameUuid(left: String, right: String): Boolean = left.equals(right, ignoreCase = true)

internal fun writeWithResponse(mode: WriteMode, properties: Set<BleCharProperty>): Boolean = when (mode) {
    WriteMode.WithResponse -> true
    WriteMode.NoResponse -> false
    WriteMode.AndroidDefault -> BleCharProperty.WriteNoResponse !in properties
}
