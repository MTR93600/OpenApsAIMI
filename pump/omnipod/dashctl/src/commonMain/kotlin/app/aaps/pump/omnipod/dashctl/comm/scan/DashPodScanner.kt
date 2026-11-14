package app.aaps.pump.omnipod.dashctl.comm.scan

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent

/** What one advertisement says, by the rules of Android `BleDiscoveredDevice`. */
sealed class DashAdvertisement {

    /** A Dash pod with the pod id that was asked for. */
    data class Pod(val podId: Long, val lotNo: Long, val sequenceNo: Int) : DashAdvertisement()

    /** Not the pod asked for (Android `DiscoveredInvalidPodException`). */
    data class Invalid(val reason: String) : DashAdvertisement()
}

/**
 * The pod advertisement, port of Android `BleDiscoveredDevice` (legacy scan) and the constants of
 * Android `PodScanner`.
 *
 * A Dash pod lists 9 service UUIDs in its advertisement, and the pod writes data into them:
 * - 1st: 4024, the Dash service (the scan filters on it);
 * - 3rd: 000a;
 * - 4th and 5th: the pod id, as 8 hex digits. A pod that is not activated yet says ffff fffe
 *   ([POD_ID_NOT_ACTIVATED]);
 * - 6th to 9th: lot number and pod sequence number.
 *
 * Each UUID is read as Android `extractUUID16` does: characters 4 to 8 of the 128-bit text form
 * ("0000xxxx-0000-1000-8000-00805f9b34fb"). CoreBluetooth gives 16-bit UUIDs, and
 * `CoreBluetoothCentral` expands them to that same text form, so the same characters are read. The
 * order of the list is used as Android does it, and as OmniBLE does it on iOS.
 */
object DashPodAdvertisement {

    /** Android `PodScanner.SCAN_FOR_SERVICE_UUID`, the scan filter. */
    const val SCAN_FOR_SERVICE_UUID = "00004024-0000-1000-8000-00805f9b34fb"

    /** Android `PodScanner.POD_ID_NOT_ACTIVATED`: the pod id a pod that can be paired advertises. */
    const val POD_ID_NOT_ACTIVATED = 0xFFFFFFFEL

    /** Android `BleDiscoveredDevice.MAIN_SERVICE_UUID`. */
    const val MAIN_SERVICE_UUID = "4024"

    /** Android `BleDiscoveredDevice.UNKNOWN_THIRD_SERVICE_UUID`. */
    const val UNKNOWN_THIRD_SERVICE_UUID = "000a"

    /**
     * Android `BleDiscoveredDevice` init: `validateServiceUUIDs`, `validatePodId`, then the lot and
     * the sequence number. The same checks in the same order, with the same texts.
     *
     * Small difference: a UUID text too short to hold characters 4 to 8, or characters that are not
     * hex, give [DashAdvertisement.Invalid]. Android cannot get such a text (a `ParcelUuid` always
     * prints 36 characters) and would throw.
     */
    fun check(serviceUuids: List<String>, podId: Long): DashAdvertisement {
        if (serviceUuids.size != 9) {
            return DashAdvertisement.Invalid("Expected 9 service UUIDs, got" + serviceUuids.size)
        }
        val short = serviceUuids.map { extractUUID16(it) ?: return DashAdvertisement.Invalid("Service UUID $it is too short") }
        if (short[0] != MAIN_SERVICE_UUID) {
            return DashAdvertisement.Invalid("The first exposed service UUID should be 4024, got " + short[0])
        }
        if (short[2] != UNKNOWN_THIRD_SERVICE_UUID) {
            return DashAdvertisement.Invalid("The third exposed service UUID should be 000a, got " + serviceUuids[2])
        }
        val hexPodId = short[3] + short[4]
        val foundPodId = hexPodId.toLongOrNull(16) ?: return DashAdvertisement.Invalid("Pod id $hexPodId is not hex")
        if (podId != foundPodId) {
            return DashAdvertisement.Invalid("This is not the POD we are looking for: $podId . Found: $foundPodId/$hexPodId")
        }
        val lotNo = (short[5] + short[6] + short[7]).substring(0, 10).toLongOrNull(16)
            ?: return DashAdvertisement.Invalid("Lot number is not hex")
        val sequenceNo = (short[7] + short[8]).substring(2).toIntOrNull(16)
            ?: return DashAdvertisement.Invalid("Sequence number is not hex")
        return DashAdvertisement.Pod(foundPodId, lotNo, sequenceNo)
    }

    /** Android `extractUUID16`: characters 4 to 8 of the UUID text, lower case as `ParcelUuid` prints it. */
    fun extractUUID16(uuid: String): String? = if (uuid.length < 8) null else uuid.substring(4, 8).lowercase()
}

/** Result of [DashPodScanner.scanForPod]. */
sealed class DashScanResult {

    /** Exactly one pod with the asked pod id. [id] is the peripheral id to connect to. */
    data class Found(val id: String, val pod: DashAdvertisement.Pod) : DashScanResult()

    /** No pod with the asked pod id (Android `ScanException("Not found")`). */
    object NotFound : DashScanResult()

    /** More than one pod with the asked pod id (Android `ScanFailFoundTooManyException`). */
    data class TooMany(val ids: List<String>) : DashScanResult()

    /** No scan was made: Bluetooth is not on (Android: no `BluetoothLeScanner`, `ScanException`). */
    data class Failed(val reason: String) : DashScanResult()
}

/**
 * The scan for a pod, port of Android `PodScanner.scanForPod` and `ScanCollector`:
 * - a scan filtered on the Dash service ([DashPodAdvertisement.SCAN_FOR_SERVICE_UUID]);
 * - it runs for [scanDurationMs] (Android `SCAN_DURATION_MS`, 5 s), then stops;
 * - the last advertisement of each peripheral is kept (Android keeps them in a map by address);
 * - each is checked with [DashPodAdvertisement.check]; the ones that fail are left out;
 * - exactly one left: [DashScanResult.Found]; none: [DashScanResult.NotFound]; more:
 *   [DashScanResult.TooMany], so the phone never pairs with a pod the user did not mean (two new
 *   pods next to each other).
 *
 * As Android, a scan failure from the platform is only logged (Android's `ScanCollector` never sets
 * its `scanFailed` field), so it ends as "not found" when nothing was seen.
 *
 * Events: the owner of the central gives every [BleCentralEvent] to [onEvent] (from the radio
 * queue). [scanForPod] blocks for the scan time; call it from a worker thread, never from the radio
 * queue.
 */
class DashPodScanner(
    private val central: BleCentral,
    private val aapsLogger: AAPSLogger,
    /** Blocks the calling thread. Tests pass a fake clock. */
    private val sleep: (Long) -> Unit,
    private val scanDurationMs: Long = SCAN_DURATION_MS,
) {

    private val lock = AapsLock()

    /** The advertisements of the scan that runs now, by peripheral id. Null when no scan runs. */
    private var collecting: MutableMap<String, BleCentralEvent.ScanResult>? = null

    /** Give every event of the central here. Never blocks for long, never calls the central. */
    fun onEvent(event: BleCentralEvent) {
        when (event) {
            is BleCentralEvent.ScanResult -> lock.withLock { collecting?.put(event.id, event) }
            is BleCentralEvent.ScanFailed -> aapsLogger.warn(LTag.PUMPBTCOMM, "Scan failed: ${event.message}")
            else                          -> Unit
        }
    }

    /** See the class KDoc. [podId] is [DashPodAdvertisement.POD_ID_NOT_ACTIVATED] for a new pod. */
    fun scanForPod(podId: Long): DashScanResult {
        val adapter = central.adapterState()
        if (adapter != BleAdapterState.PoweredOn) {
            return DashScanResult.Failed("Bluetooth not available ($adapter)")
        }
        lock.withLock { collecting = LinkedHashMap() }
        aapsLogger.debug(LTag.PUMPBTCOMM, "Scanning for pod $podId with filter ${DashPodAdvertisement.SCAN_FOR_SERVICE_UUID}")
        try {
            central.startScan(listOf(DashPodAdvertisement.SCAN_FOR_SERVICE_UUID))
            sleep(scanDurationMs)
        } finally {
            central.stopScan()
        }
        val seen = lock.withLock {
            val all = collecting?.values?.toList().orEmpty()
            collecting = null
            all
        }
        return collect(seen, podId)
    }

    /** Android `ScanCollector.collect` and the end of `scanForPod`. */
    private fun collect(seen: List<BleCentralEvent.ScanResult>, podId: Long): DashScanResult {
        aapsLogger.debug(LTag.PUMPBTCOMM, "ScanCollector looking for podID: $podId")
        val found = seen.mapNotNull { result ->
            when (val checked = DashPodAdvertisement.check(result.serviceUuids, podId)) {
                is DashAdvertisement.Pod     -> result.id to checked
                is DashAdvertisement.Invalid -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "ScanCollector: pod not matching ${checked.reason}")
                    null
                }
            }
        }
        return when (found.size) {
            0    -> DashScanResult.NotFound
            1    -> DashScanResult.Found(found[0].first, found[0].second)
            else -> DashScanResult.TooMany(found.map { it.first })
        }
    }

    companion object {

        /** Android `PodScanner.SCAN_DURATION_MS`. */
        const val SCAN_DURATION_MS = 5_000L
    }
}
