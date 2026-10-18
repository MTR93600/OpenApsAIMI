package app.aaps.pump.medtrum.ios

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.medtrum.comm.ManufacturerData
import app.aaps.pump.medtrum.util.MedtrumDeviceName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One Medtrum pump seen in a scan. */
data class MedtrumScannedPump(

    /** What the person reads in the list, for example `MT-2F4A1B`. */
    val name: String,

    /**
     * The CoreBluetooth peripheral id. It is not a MAC address: iOS never gives one, and the id
     * is different on every iPhone.
     */
    val id: String,

    /** The serial number. This is what the driver connects with. */
    val serial: Long
) {

    /** The serial as the hex text that is stored. Android stores the very same text. */
    val serialHex: String get() = MedtrumDeviceName.serialHex(serial)
}

/**
 * The serial in one iOS advertisement, or `null` when the advertisement is not a Medtrum pump.
 *
 * CoreBluetooth hands the whole manufacturer field over, so the first two bytes are the company
 * id in little endian. Android's `getManufacturerSpecificData` strips those two bytes itself,
 * which is why only the iOS side needs this step.
 */
internal fun medtrumSerialFromAdvertisement(advertisementBytes: ByteArray?): Long? {
    val bytes = advertisementBytes ?: return null
    if (bytes.size < COMPANY_ID_BYTES + MANUFACTURER_DATA_BYTES) return null
    val companyId = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    if (companyId != IosMedtrumBleTransport.MANUFACTURER_ID) return null
    val serial = try {
        ManufacturerData(bytes.copyOfRange(COMPANY_ID_BYTES, bytes.size)).getDeviceSN()
    } catch (e: Exception) {
        return null
    }
    return if (serial > 0L) serial else null
}

/** iOS manufacturer payload = 2-byte little endian company id + company data. */
private const val COMPANY_ID_BYTES = 2

/** Medtrum company data = 4-byte serial + device type + version. */
private const val MANUFACTURER_DATA_BYTES = 6

/**
 * Finds Medtrum pumps nearby and reads their serial number straight out of the advertisement.
 *
 * This is the iOS answer to the Android `MedtrumScannerImpl`, which lives inside
 * `MedtrumBleTransportImpl` as its `scanner` property and not in the shared
 * [app.aaps.pump.medtrum.session.MedtrumBleTransport] interface. The same split is kept here: the
 * shared interface still only knows connect-by-serial, and discovery sits beside it on
 * [IosMedtrumBleTransport.scanner]. The transport owns the radio and already receives every
 * central event, so it is also the one place that can hand scan results to both readers without
 * two objects fighting over one scan.
 *
 * Nothing is invented. A device is offered only when a serial can really be read from it, so an
 * empty list means no pump was found, never "a pump with serial 0".
 *
 * @param central the radio.
 * @param connectScanActive tells whether the connect path is scanning for its own pump. Only the
 *   owner of a scan may stop it, otherwise the discovery step would cut a connect attempt short.
 */
class IosMedtrumPumpScanner internal constructor(
    private val central: BleCentral,
    private val connectScanActive: () -> Boolean
) {

    private val _pumps = MutableStateFlow<List<MedtrumScannedPump>>(emptyList())

    /** The pumps found since the last [startScan], in the order they answered. */
    val pumps: StateFlow<List<MedtrumScannedPump>> = _pumps.asStateFlow()

    /** True between [startScan] and [stopScan]. */
    var scanning: Boolean = false
        private set

    /**
     * Starts a scan and empties the list. Returns false when Bluetooth is not on, in which case
     * nothing is scanning and the list stays empty.
     */
    fun startScan(): Boolean {
        if (central.adapterState() != BleAdapterState.PoweredOn) return false
        _pumps.value = emptyList()
        scanning = true
        central.startScan()
        return true
    }

    /** Stops the scan. The list of pumps found so far is kept, so a choice can still be made. */
    fun stopScan() {
        if (!scanning) return
        scanning = false
        if (!connectScanActive()) central.stopScan()
    }

    /**
     * One advertisement. Called by [IosMedtrumBleTransport.onEvent], which receives every central
     * event; tests may call it directly.
     */
    internal fun onScanResult(event: BleCentralEvent.ScanResult) {
        if (!scanning) return
        // The serial is the proof that this is a Medtrum pump. No serial, no entry in the list.
        val serial = medtrumSerialFromAdvertisement(event.advertisementBytes)
            ?: MedtrumDeviceName.serialFromName(event.name)
            ?: return
        if (_pumps.value.any { it.id == event.id }) return
        _pumps.value = _pumps.value + MedtrumScannedPump(
            name = MedtrumDeviceName.displayName(serial),
            id = event.id,
            serial = serial
        )
    }
}
