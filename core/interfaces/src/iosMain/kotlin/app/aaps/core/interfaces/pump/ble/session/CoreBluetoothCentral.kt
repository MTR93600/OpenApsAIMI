package app.aaps.core.interfaces.pump.ble.session

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
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
import platform.CoreBluetooth.CBUUID
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
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [BleCentral] on CoreBluetooth.
 *
 * One serial queue owns the manager, the peripheral map and every callback. Callers from another
 * thread go through [sync] or [post]. Delegate methods already run on that queue.
 *
 * **State restoration.** [restoreIdentifier] is passed as `CBCentralManagerOptionRestoreIdentifierKey`,
 * and `willRestoreState` is read. This is what makes iOS start the app again for a Bluetooth event
 * after the system has reclaimed it or the phone has rebooted. Without it the app only runs while
 * somebody keeps it alive, so the loop works during the day and stops in the night. The peripherals
 * iOS hands back are put into the peripheral map again with the delegate set, and reported through
 * [BleCentralEvent.Restored]. What is not usable is reported too, never dropped in silence.
 *
 * The identifier has to be the same string at every launch and has to differ from every other
 * central in the process. [BleRestoreIdentifier] holds the agreed values. Opening a second central
 * on an identifier that is already open in this process does not get the key, and says so through
 * [BleCentralEvent.OperationFailed].
 *
 * Restoration does not happen after the person force-quits the app, nor while Bluetooth is off in
 * Settings, and it does not happen on a normal start from the home screen. The rest of the iOS
 * notes are in `_docs/kmp/ios-ble-transport-ecarts.md`.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreBluetoothCentral(
    private val restoreIdentifier: String = BleRestoreIdentifier.PUMP_TRANSPORT
) : BleCentral {

    private val queue = dispatch_queue_create("app.aaps.ble", null)
    private val queueKey = StableQueueKey()
    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private val characteristics = mutableMapOf<String, CBCharacteristic>()
    private val delegate = RadioDelegate(::emit, ::onRestore)

    // Written on the caller thread by open() and on the radio queue by onRestore, read from both.
    @Volatile
    private var manager: CBCentralManager? = null

    var events: ((BleCentralEvent) -> Unit)? = null

    init {
        dispatch_queue_set_specific(queue, queueKey.pointer, queueKey.pointer, null)
        delegate.peripherals = peripherals
        delegate.characteristics = characteristics
    }

    /**
     * Creates the manager. Call after [events] is set, always.
     *
     * `centralManagerDidUpdateState` can run as soon as the manager exists, and it has to reach the
     * session. `willRestoreState` is stricter still: iOS delivers it during the manager's own set
     * up, before the state update, so a central opened with no listener loses the one event that
     * says which peripherals came back.
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun open() {
        if (manager != null) return
        if (!ClaimedRestoreIdentifiers.claim(restoreIdentifier)) {
            // Two managers on one identifier would be offered the same kept state. This one opens
            // without the key, which costs it restoration and nothing else, and it is said out
            // loud rather than discovered on a morning with no loop.
            emit(
                BleCentralEvent.OperationFailed(
                    operation = "restore",
                    message = "restore identifier $restoreIdentifier is already open in this process; this central runs without restoration"
                )
            )
            manager = CBCentralManager(delegate = delegate, queue = queue, options = null)
            return
        }
        val options = mapOf<Any?, Any?>(CBCentralManagerOptionRestoreIdentifierKey to restoreIdentifier)
        manager = CBCentralManager(delegate = delegate, queue = queue, options = options)
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

    /**
     * The name CoreBluetooth holds for a peripheral we know.
     *
     * Works for a restored id too: a restored peripheral is in the map, and it is connected, so its
     * GAP name is there. This is how a driver that stores no peripheral identifier can tell whether
     * a restored peripheral is its own device.
     */
    override fun cachedName(id: String): String? = peripherals[id]?.name

    private fun emit(event: BleCentralEvent) {
        events?.invoke(event)
    }

    /**
     * Takes back the peripherals iOS kept while the app was gone.
     *
     * Runs on the radio queue, from `centralManager:willRestoreState:`. iOS hands back every
     * peripheral that was connected, or still trying to connect, when it ended the app. None of
     * them carries a delegate: iOS keeps the peripheral, not our object graph. So the delegate is
     * set again here. Without that line the peripheral's notifications reach nobody, and being
     * started again would buy nothing.
     *
     * Each peripheral also goes into the peripheral map, which is what [connect], [cancel],
     * [release] and [cachedName] all look up. An id that is not in that map is an id a driver can
     * do nothing with, not even hang up on. So a driver that decides not to take a restored link
     * over can still call [cancel] or [release] on it straight away, and that is what both iOS
     * drivers do today.
     *
     * Entries that are not peripherals are counted and reported by [bleRestoreEvents]. Nothing is
     * dropped without a word.
     */
    private fun onRestore(central: CBCentralManager, state: Map<Any?, *>) {
        // The queue can deliver this before open() has stored the manager it is building. Taking
        // the manager from the callback removes that race, so the central is usable from inside
        // this event and from anything the event starts.
        if (manager == null) manager = central
        val raw = (state[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>).orEmpty()
        val restored = raw.filterIsInstance<CBPeripheral>()
        val ids = restored.map { peripheral ->
            val id = peripheral.identifier.UUIDString
            peripherals[id] = peripheral
            peripheral.delegate = delegate
            indexRestoredCharacteristics(id, peripheral)
            id
        }
        bleRestoreEvents(ids, dropped = raw.size - restored.size).forEach { emit(it) }
    }

    /**
     * Puts back the characteristics CoreBluetooth already holds for a restored peripheral.
     *
     * `CBCentralManagerRestoredStatePeripheralsKey` says iOS restores the discovered services,
     * characteristics and descriptors "when possible", so sometimes they are there and sometimes
     * they are not. This fills the map when there is something to fill it with and leaves it empty
     * otherwise. A driver still has to discover services again before it can count on finding
     * anything here.
     */
    private fun indexRestoredCharacteristics(id: String, peripheral: CBPeripheral) {
        peripheral.services.orEmpty().filterIsInstance<CBService>().forEach { service ->
            service.characteristics.orEmpty().filterIsInstance<CBCharacteristic>().forEach { characteristic ->
                characteristics[key(id, expandUuid(characteristic.UUID.UUIDString))] = characteristic
            }
        }
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
        private val emit: (BleCentralEvent) -> Unit,
        private val restore: (CBCentralManager, Map<Any?, *>) -> Unit
    ) : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

        var peripherals: MutableMap<String, CBPeripheral> = mutableMapOf()
        var characteristics: MutableMap<String, CBCharacteristic> = mutableMapOf()

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            emit(BleCentralEvent.AdapterState(central.state.toAdapterState()))
        }

        /**
         * iOS started the app again for a Bluetooth event. This is the first delegate call of the
         * launch, before `centralManagerDidUpdateState`, and it is the only place the kept
         * peripherals are offered.
         */
        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            restore(central, willRestoreState)
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
            val advertised = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)
                .orEmpty()
                .filterIsInstance<CBUUID>()
                .map { expandUuid(it.UUIDString) }
            emit(
                BleCentralEvent.ScanResult(
                    name = name,
                    id = id,
                    advertisementBytes = manufacturer?.toByteArray(),
                    serviceUuids = advertised
                )
            )
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

/**
 * The restore identifiers already given to a `CBCentralManager` in this process.
 *
 * iOS keys the kept state by this string, so two managers sharing one would be offered each
 * other's peripherals. The second central to ask for a name is refused and opens without the key,
 * which is visible, instead of quietly fighting the first one over the same saved state.
 */
@OptIn(ExperimentalAtomicApi::class)
private object ClaimedRestoreIdentifiers {

    private val taken = AtomicReference<Set<String>>(emptySet())

    /** True the first time [identifier] is claimed in this process, false afterwards. */
    fun claim(identifier: String): Boolean {
        while (true) {
            val current = taken.load()
            if (identifier in current) return false
            if (taken.compareAndSet(current, current + identifier)) return true
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
