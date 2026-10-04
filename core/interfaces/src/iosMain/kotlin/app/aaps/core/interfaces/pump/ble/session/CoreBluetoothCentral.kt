package app.aaps.core.interfaces.pump.ble.session

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time
import platform.posix.memcpy

/**
 * [BleCentral] on CoreBluetooth.
 *
 * One serial queue owns the manager, the peripheral map and every callback. Callers from another
 * thread go through [sync] or [post]. Delegate methods already run on that queue.
 *
 * No restore identifier is passed to `CBCentralManager`. [BleTransport] has nowhere to put
 * `willRestoreState`, and setting the key without reading the result would drop the peripherals
 * iOS relaunches the app to deliver. The gap is written up in
 * `_docs/kmp/ios-ble-transport-ecarts.md`.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreBluetoothCentral : BleCentral {

    private val queue = dispatch_queue_create("app.aaps.ble", null)
    private val queueKey = StableQueueKey()
    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private val characteristics = mutableMapOf<String, CBCharacteristic>()
    private val delegate = RadioDelegate(::emit)
    private var manager: CBCentralManager? = null

    var events: ((BleCentralEvent) -> Unit)? = null

    init {
        dispatch_queue_set_specific(queue, queueKey.pointer, queueKey.pointer, null)
        delegate.peripherals = peripherals
        delegate.characteristics = characteristics
    }

    /**
     * Creates the manager. Call after [events] is set: `centralManagerDidUpdateState` can run as
     * soon as the manager exists, and it has to reach the session.
     */
    fun open() {
        if (manager != null) return
        // options is null on purpose: see the class KDoc. Do not add a restore key here.
        manager = CBCentralManager(delegate = delegate, queue = queue, options = null)
    }

    fun postAfter(millis: Long, block: () -> Unit) {
        val delay = dispatch_time(DISPATCH_TIME_NOW, millis * 1_000_000L)
        dispatch_after(delay, queue, block)
    }

    fun sync(block: () -> Unit) {
        if (onQueue()) block() else dispatch_sync(queue, block)
    }

    fun <T> syncValue(block: () -> T): T {
        if (onQueue()) return block()
        var value: T? = null
        var written = false
        dispatch_sync(queue) {
            value = block()
            written = true
        }
        if (!written) error("CoreBluetooth queue did not run the block")
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** Runs after the caller returns. Used for a write-without-response completion. */
    fun post(block: () -> Unit) {
        dispatch_async(queue, block)
    }

    override fun adapterState(): BleAdapterState = manager?.state.toAdapterState()

    override fun startScan() {
        manager?.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
            ?: error("CoreBluetooth central is not open")
    }

    override fun stopScan() {
        manager?.stopScan()
    }

    override fun connect(id: String): Boolean {
        val peripheral = peripheralFor(id) ?: return false
        peripheral.delegate = delegate
        val central = manager ?: return false
        central.connectPeripheral(peripheral, options = null)
        return true
    }

    override fun cancel(id: String) {
        val peripheral = peripherals[id] ?: return
        manager?.cancelPeripheralConnection(peripheral)
    }

    override fun release(id: String) {
        val peripheral = peripherals.remove(id) ?: return
        manager?.cancelPeripheralConnection(peripheral)
        characteristics.keys.filter { it.startsWith("$id|") }.forEach { characteristics.remove(it) }
    }

    override fun discoverServices(id: String) {
        val peripheral = peripherals[id] ?: return
        peripheral.delegate = delegate
        peripheral.discoverServices(serviceUUIDs = null)
    }

    override fun discoverCharacteristics(id: String, serviceUuid: String) {
        val peripheral = peripherals[id] ?: return
        val service = peripheral.services.orEmpty().filterIsInstance<CBService>().firstOrNull {
            sameUuid(expandUuid(it.UUID.UUIDString), serviceUuid)
        } ?: return
        peripheral.discoverCharacteristics(characteristicUUIDs = null, forService = service)
    }

    override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean {
        val peripheral = peripherals[id] ?: return false
        val characteristic = characteristics[key(id, characteristicUuid)] ?: return false
        val type = if (withResponse) CBCharacteristicWriteWithResponse else CBCharacteristicWriteWithoutResponse
        peripheral.writeValue(data.toNsData(), forCharacteristic = characteristic, type = type)
        return true
    }

    override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean {
        val peripheral = peripherals[id] ?: return false
        val characteristic = characteristics[key(id, characteristicUuid)] ?: return false
        // CoreBluetooth picks notification versus indication from the characteristic properties.
        // [indicate] is the Android CCCD choice, recorded by the session; the stack writes the descriptor.
        peripheral.setNotifyValue(true, forCharacteristic = characteristic)
        return true
    }

    override fun cachedName(id: String): String? = peripherals[id]?.name

    private fun emit(event: BleCentralEvent) {
        events?.invoke(event)
    }

    private fun onQueue(): Boolean = dispatch_get_specific(queueKey.pointer) != null

    private fun peripheralFor(id: String): CBPeripheral? {
        peripherals[id]?.let { return it }
        val uuid = NSUUID(uUIDString = id)
        val found = manager?.retrievePeripheralsWithIdentifiers(listOf(uuid))
            ?.filterIsInstance<CBPeripheral>()
            ?.firstOrNull()
            ?: return null
        found.delegate = delegate
        peripherals[id] = found
        return found
    }

    private class RadioDelegate(
        private val emit: (BleCentralEvent) -> Unit
    ) : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

        var peripherals: MutableMap<String, CBPeripheral> = mutableMapOf()
        var characteristics: MutableMap<String, CBCharacteristic> = mutableMapOf()

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            emit(BleCentralEvent.AdapterState(central.state.toAdapterState()))
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: platform.Foundation.NSNumber
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            peripherals[id] = didDiscoverPeripheral
            val local = advertisementData[CBAdvertisementDataLocalNameKey] as? String
            val name = local?.takeIf { it.isNotEmpty() } ?: didDiscoverPeripheral.name
            val manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData
            emit(BleCentralEvent.ScanResult(name = name, id = id, advertisementBytes = manufacturer?.toByteArray()))
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            val id = didConnectPeripheral.identifier.UUIDString
            peripherals[id] = didConnectPeripheral
            didConnectPeripheral.delegate = this
            emit(BleCentralEvent.Link(id, connected = true, status = 0))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            emit(BleCentralEvent.Link(didFailToConnectPeripheral.identifier.UUIDString, connected = false, status = statusOf(error)))
        }

        @ObjCSignatureOverride
        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            emit(BleCentralEvent.Link(didDisconnectPeripheral.identifier.UUIDString, connected = false, status = statusOf(error)))
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val id = peripheral.identifier.UUIDString
            val failure = statusOf(didDiscoverServices)
            if (failure != 0) {
                emit(BleCentralEvent.Services(id, emptyList(), failure))
                return
            }
            val services = peripheral.services.orEmpty().filterIsInstance<CBService>().map { service ->
                BleGattService(uuid = expandUuid(service.UUID.UUIDString), characteristics = emptyList())
            }
            emit(BleCentralEvent.Services(id, services, status = 0))
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            val id = peripheral.identifier.UUIDString
            val serviceUuid = expandUuid(didDiscoverCharacteristicsForService.UUID.UUIDString)
            val failure = statusOf(error)
            if (failure != 0) {
                emit(BleCentralEvent.Characteristics(id, serviceUuid, emptyList(), failure))
                return
            }
            val found = didDiscoverCharacteristicsForService.characteristics.orEmpty()
                .filterIsInstance<CBCharacteristic>()
                .map { characteristic ->
                    val uuid = expandUuid(characteristic.UUID.UUIDString)
                    characteristics[key(id, uuid)] = characteristic
                    BleGattCharacteristic(uuid, characteristic.properties.toProperties())
                }
            emit(BleCentralEvent.Characteristics(id, serviceUuid, found, status = 0))
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didWriteValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            emit(
                BleCentralEvent.Written(
                    id = peripheral.identifier.UUIDString,
                    characteristicUuid = expandUuid(didWriteValueForCharacteristic.UUID.UUIDString),
                    status = statusOf(error)
                )
            )
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            val failure = statusOf(error)
            if (failure != 0) {
                emit(BleCentralEvent.OperationFailed("notify", "${didUpdateValueForCharacteristic.UUID.UUIDString} status $failure"))
                return
            }
            val bytes = didUpdateValueForCharacteristic.value?.toByteArray() ?: ByteArray(0)
            emit(
                BleCentralEvent.Value(
                    id = peripheral.identifier.UUIDString,
                    characteristicUuid = expandUuid(didUpdateValueForCharacteristic.UUID.UUIDString),
                    data = bytes
                )
            )
        }

        /**
         * One completion per `setNotifyValue`, which is the CCCD write CoreBluetooth performs.
         * `didWriteValueForDescriptor` is not also forwarded: Dana treats a second
         * [app.aaps.core.interfaces.pump.ble.BleTransportListener.onDescriptorWritten] as a second
         * registration.
         */
        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            emit(
                BleCentralEvent.DescriptorWritten(
                    id = peripheral.identifier.UUIDString,
                    descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
                    status = statusOf(error)
                )
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class StableQueueKey {
    private val ref = kotlinx.cinterop.StableRef.create(this)
    val pointer = ref.asCPointer()
}

@OptIn(ExperimentalForeignApi::class)
private fun platform.CoreBluetooth.CBManagerState?.toAdapterState(): BleAdapterState = when (this) {
    CBManagerStateUnknown -> BleAdapterState.Unknown
    CBManagerStateResetting -> BleAdapterState.Resetting
    CBManagerStateUnsupported -> BleAdapterState.Unsupported
    CBManagerStateUnauthorized -> BleAdapterState.Unauthorized
    CBManagerStatePoweredOff -> BleAdapterState.PoweredOff
    CBManagerStatePoweredOn -> BleAdapterState.PoweredOn
    else -> BleAdapterState.Unknown
}

private fun statusOf(error: NSError?): Int {
    if (error == null) return 0
    val code = error.code.toInt()
    return if (code == 0) 1 else code
}

private fun key(id: String, uuid: String): String = "$id|${uuid.lowercase()}"

internal fun expandUuid(raw: String): String {
    val hex = raw.lowercase()
    return when (hex.length) {
        4 -> "0000$hex-0000-1000-8000-00805f9b34fb"
        8 -> "$hex-0000-1000-8000-00805f9b34fb"
        else -> hex
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun platform.darwin.NSUInteger.toProperties(): Set<BleCharProperty> {
    val bits = this
    val found = mutableSetOf<BleCharProperty>()
    if (bits and CBCharacteristicPropertyRead != 0uL) found += BleCharProperty.Read
    if (bits and CBCharacteristicPropertyWrite != 0uL) found += BleCharProperty.Write
    if (bits and CBCharacteristicPropertyWriteWithoutResponse != 0uL) found += BleCharProperty.WriteNoResponse
    if (bits and CBCharacteristicPropertyNotify != 0uL) found += BleCharProperty.Notify
    if (bits and CBCharacteristicPropertyIndicate != 0uL) found += BleCharProperty.Indicate
    return found
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length)
    }
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNsData(): NSData {
    if (isEmpty()) return NSData()
    // ObjCClassOf.create(bytes, length) is initWithBytes:length:, which copies.
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
