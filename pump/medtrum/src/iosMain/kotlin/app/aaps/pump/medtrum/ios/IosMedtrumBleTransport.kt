package app.aaps.pump.medtrum.ios

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.medtrum.comm.ManufacturerData
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.ReadDataPacket
import app.aaps.pump.medtrum.comm.WriteCommandPackets
import app.aaps.pump.medtrum.session.MedtrumBleCallback
import app.aaps.pump.medtrum.session.MedtrumBleTransport
import app.aaps.pump.medtrum.util.MedtrumDeviceName

/**
 * iOS implementation of [MedtrumBleTransport] on [BleCentral] (CoreBluetooth).
 *
 * Pure byte transport — zero protocol logic. All framing, sequencing and
 * reassembly use the M1 primitives ([WriteCommandPackets], [ReadDataPacket],
 * [ManufacturerData]); the session (M2) owns the auth flow and commands.
 *
 * Event wiring: the owner sets `central.events = transport::onEvent` when the
 * central is a `CoreBluetoothCentral`. Tests drive [onEvent] directly with a
 * [FakeBleCentral].
 *
 * Flow (mirrors Android `MedtrumBleTransportImpl`):
 * - [connect]: scan → match pump SN in manufacturer data (company 0x4781) →
 *   connect → discover services → discover characteristics → enable notify on
 *   READ_UUID + indicate on WRITE_UUID → [MedtrumBleCallback.onConnected].
 * - [sendMessage]: chunk via [WriteCommandPackets], write on WRITE_UUID
 *   (with response), continue on each `Written` event.
 * - Incoming: READ_UUID → [MedtrumBleCallback.onNotification];
 *   WRITE_UUID → reassemble via [ReadDataPacket] → [MedtrumBleCallback.onIndication].
 *
 * State restoration: iOS can start the app again and hand back the peripherals it kept. This
 * transport never takes such a link over; see [onRestored] for why and for what it does instead.
 *
 * @param pumpSerial the serial of the pump this app is paired with, read when it is needed rather
 *   than copied, because it is only known after the stored state has been loaded. Zero means no
 *   pump has been chosen yet.
 * @param logger where the restore lines go. Tests pass a recording logger.
 */
class IosMedtrumBleTransport(
    private val central: BleCentral,
    private val pumpSerial: () -> Long = { 0L },
    private val logger: MedtrumLogger = MedtrumLogger.NO_OP,
) : MedtrumBleTransport {

    companion object {
        const val SERVICE_UUID = "669A9001-0008-968F-E311-6050405558B3"
        const val READ_UUID = "669a9120-0008-968f-e311-6050405558b3"
        const val WRITE_UUID = "669a9101-0008-968f-e311-6050405558b3"

        /** Medtrum company ID in BLE manufacturer data (0x4781). */
        const val MANUFACTURER_ID = 18305

        /** Log tag of this transport. The AAPS logger puts it in front of the line. */
        private const val TAG = "PUMPBTCOMM"
    }

    /**
     * Discovery: finds pumps nearby without knowing a serial first. It sits beside the shared
     * interface, not inside it, exactly as the Android driver's own `scanner` property does.
     */
    val scanner: IosMedtrumPumpScanner = IosMedtrumPumpScanner(central) { scanning }

    private var callback: MedtrumBleCallback? = null
    private var targetSN: Long = 0L
    private var linkId: String? = null
    private var scanning = false

    private var writePackets: WriteCommandPackets? = null
    private var writeSequenceNumber = 0
    private var readPacket: ReadDataPacket? = null
    private val readLock = AapsLock()

    /** Tracks descriptor writes so [MedtrumBleCallback.onConnected] fires once both are armed. */
    private var descriptorsArmed = 0

    override fun setCallback(callback: MedtrumBleCallback?) {
        this.callback = callback
    }

    override fun connect(from: String, deviceSN: Long): Boolean {
        if (central.adapterState() != BleAdapterState.PoweredOn) return false
        targetSN = deviceSN
        descriptorsArmed = 0
        scanning = true
        central.startScan()
        return true
    }

    override fun disconnect(from: String) {
        scanning = false
        central.stopScan()
        linkId?.let { central.cancel(it) }
        // linkId is cleared by the Link(connected=false) event → onDisconnected.
        writePackets = null
    }

    override fun sendMessage(message: ByteArray) {
        if (writePackets?.allPacketsConsumed() == false) {
            callback?.onSendMessageError("previous packets not consumed, dropping", false)
            return
        }
        val id = linkId ?: run {
            callback?.onSendMessageError("not connected", false)
            return
        }
        writePackets = WriteCommandPackets(message, writeSequenceNumber)
        writeSequenceNumber = (writeSequenceNumber + 1) % 256
        val first = writePackets?.getNextPacket()
        if (first != null) {
            central.write(id, WRITE_UUID, first, withResponse = true)
        } else {
            callback?.onSendMessageError("error in writePacket!", false)
        }
    }

    /**
     * Feed central events here. The owner wires
     * `central.events = transport::onEvent` for a `CoreBluetoothCentral`;
     * tests call this directly.
     */
    fun onEvent(event: BleCentralEvent) {
        // Every case is written out. There is no `else`, so a new event in the sealed class stops
        // the compiler here instead of being dropped without a word.
        when (event) {
            is BleCentralEvent.ScanResult        -> onScanResult(event)
            is BleCentralEvent.Link              -> onLink(event)
            is BleCentralEvent.Services          -> onServices(event)
            is BleCentralEvent.Characteristics   -> onCharacteristics(event)
            is BleCentralEvent.Written           -> onWritten(event)
            is BleCentralEvent.Value             -> onValue(event)
            is BleCentralEvent.DescriptorWritten -> onDescriptorWritten(event)
            is BleCentralEvent.Restored          -> onRestored(event)
            // The radio state is read in [connect], not followed here. It is logged so that a scan
            // which never finds the pump can be read back against the radio going off.
            is BleCentralEvent.AdapterState      -> logger.debug(TAG, "adapter state ${event.state}")
            is BleCentralEvent.ScanFailed        -> logger.warn(TAG, "scan failed: ${event.message}")
            is BleCentralEvent.OperationFailed   -> logger.warn(TAG, "${event.operation} failed: ${event.message}")
        }
    }

    /**
     * iOS started the app again for a Bluetooth event and handed back the peripherals it kept.
     *
     * Medtrum never carries on with a restored link. Every peripheral iOS returned is hung up on
     * and the link state here is cleared, so the next connect starts from a scan like any other.
     *
     * Why not take the link over. Nothing this transport counts per link survived the relaunch.
     * [writeSequenceNumber] would start at 0 again on a link the pump never saw drop, which is not
     * a case the protocol covers, and [readPacket] is empty, so the first indication of an answer
     * that was already running would be read as the head of a new message: it would pass its own
     * CRC and give a wrong length. The session above is worse off still. `MedtrumSession` does not
     * check its phase in `onNotification`, so a restored link would write pump state, reservoir,
     * bolus progress and temp basal history from packets that no handshake stands behind, and
     * `bolusDone` reads back as true after a relaunch, so a bolus could be sent to a pump that is
     * still delivering the one that was in flight when iOS ended the app.
     *
     * What was missed while the app was dead is recovered the way the driver is built to recover
     * it: a fresh auth flow and then a record sync over the stored sequence numbers. The patch
     * session token is stored as well, so authenticating again costs the person nothing.
     *
     * This only cleans up the radio. It does not set `MedtrumSession.phase`, it does not set
     * `MedtrumSessionState.connectionState`, and it does not touch a therapy value. It also does
     * not start a connect: the command queue owns when the driver talks to the pump, and a connect
     * started from here would reach `MedtrumSession.onConnected` with no command behind it and
     * leave the session READY on its own.
     *
     * A peripheral that cannot be named is released all the same. iOS hands back what was still
     * only trying to connect, and such a peripheral has no name yet, so "unknown" must never mean
     * "left alone": a device left connected at the radio while the app believes it owns nothing is
     * invisible from the outside.
     */
    private fun onRestored(event: BleCentralEvent.Restored) {
        val ourSerial = pumpSerial()
        val liveLink = linkId
        logger.warn(TAG, "iOS restored ${event.ids.size} peripherals; none of them is taken over")
        for (id in event.ids) {
            val name = central.cachedName(id)
            val serial = MedtrumDeviceName.serialFromName(name)
            val found = when {
                name == null                           -> "no name yet, so iOS gave it back while it was still connecting"
                serial == null                         -> "the name '$name' is not a Medtrum pump"
                ourSerial == 0L                        -> "the pump '$name', but no serial is stored here to compare it with"
                serial == ourSerial                    -> "our pump '$name'"
                else                                   -> "another Medtrum pump '$name'"
            }
            logger.warn(TAG, "restored $id is $found; releasing it and starting again from a scan")
            // cancel hangs up. release hangs up as well and forgets the peripheral and the
            // characteristics cached for it, so nothing stale can be written to later.
            central.cancel(id)
            central.release(id)
        }
        // Only clear the link state that the restore actually took away. A live link that is not
        // in the list keeps its sequence number and its half-read answer.
        if (liveLink != null && liveLink !in event.ids) return
        linkId = null
        writePackets = null
        writeSequenceNumber = 0
        descriptorsArmed = 0
        readLock.withLock { readPacket = null }
    }

    private fun onScanResult(event: BleCentralEvent.ScanResult) {
        // Discovery reads the same advertisements. It only looks when it is scanning itself.
        scanner.onScanResult(event)
        if (!scanning) return
        val sn = medtrumSerialFromAdvertisement(event.advertisementBytes) ?: return
        if (sn == targetSN) {
            scanning = false
            central.stopScan()
            linkId = event.id
            writeSequenceNumber = 0
            central.connect(event.id)
        }
    }

    private fun onLink(event: BleCentralEvent.Link) {
        if (event.id != linkId) return
        if (event.connected) {
            central.discoverServices(event.id)
        } else {
            linkId = null
            writePackets = null
            callback?.onDisconnected()
        }
    }

    private fun onServices(event: BleCentralEvent.Services) {
        if (event.id != linkId || event.status != 0) return
        val service = event.services.firstOrNull {
            it.uuid.equals(SERVICE_UUID, ignoreCase = true)
        } ?: return
        central.discoverCharacteristics(event.id, service.uuid)
    }

    private fun onCharacteristics(event: BleCentralEvent.Characteristics) {
        if (event.id != linkId || event.status != 0) return
        descriptorsArmed = 0
        central.enableNotify(event.id, READ_UUID, indicate = false)
        central.enableNotify(event.id, WRITE_UUID, indicate = true)
    }

    private fun onDescriptorWritten(event: BleCentralEvent.DescriptorWritten) {
        if (event.id != linkId || event.status != 0) return
        descriptorsArmed++
        // Both READ (notify) and WRITE (indicate) descriptors must be armed.
        if (descriptorsArmed >= 2) {
            descriptorsArmed = 0
            callback?.onConnected()
        }
    }

    private fun onWritten(event: BleCentralEvent.Written) {
        if (event.id != linkId) return
        if (event.status == 0) {
            writePackets?.let { packets ->
                val next = packets.getNextPacket()
                if (next != null) {
                    central.write(event.id, WRITE_UUID, next, withResponse = true)
                }
            }
        } else {
            callback?.onSendMessageError("onCharacteristicWrite failure", true)
        }
    }

    private fun onValue(event: BleCentralEvent.Value) {
        if (event.id != linkId) return
        when {
            event.characteristicUuid.equals(READ_UUID, ignoreCase = true) ->
                callback?.onNotification(event.data)
            event.characteristicUuid.equals(WRITE_UUID, ignoreCase = true) ->
                handleIndication(event.data)
        }
    }

    private fun handleIndication(value: ByteArray) {
        readLock.withLock {
            if (readPacket == null) {
                readPacket = ReadDataPacket(value)
            } else {
                readPacket?.addData(value)
            }
            if (readPacket?.allDataReceived() == true) {
                if (readPacket?.failed() == true) {
                    callback?.onSendMessageError("ReadDataPacket failed", false)
                } else {
                    readPacket?.getData()?.let { callback?.onIndication(it) }
                }
                readPacket = null
            }
        }
    }
}
