package app.aaps.pump.medtrum.ios

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.medtrum.comm.ManufacturerData
import app.aaps.pump.medtrum.comm.ReadDataPacket
import app.aaps.pump.medtrum.comm.WriteCommandPackets
import app.aaps.pump.medtrum.session.MedtrumBleCallback
import app.aaps.pump.medtrum.session.MedtrumBleTransport

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
 */
class IosMedtrumBleTransport(
    private val central: BleCentral,
) : MedtrumBleTransport {

    companion object {
        const val SERVICE_UUID = "669A9001-0008-968F-E311-6050405558B3"
        const val READ_UUID = "669a9120-0008-968f-e311-6050405558b3"
        const val WRITE_UUID = "669a9101-0008-968f-e311-6050405558b3"

        /** Medtrum company ID in BLE manufacturer data (0x4781). */
        const val MANUFACTURER_ID = 18305

        /** iOS manufacturer payload = 2-byte LE company ID + company data. */
        private const val COMPANY_ID_BYTES = 2
    }

    private var callback: MedtrumBleCallback? = null
    private var targetSN: Long = 0L
    private var linkId: String? = null
    private var scanning = false

    private var writePackets: WriteCommandPackets? = null
    private var writeSequenceNumber = 0
    private var readPacket: ReadDataPacket? = null
    private val readLock = Any()

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
        when (event) {
            is BleCentralEvent.ScanResult      -> onScanResult(event)
            is BleCentralEvent.Link            -> onLink(event)
            is BleCentralEvent.Services        -> onServices(event)
            is BleCentralEvent.Characteristics -> onCharacteristics(event)
            is BleCentralEvent.Written          -> onWritten(event)
            is BleCentralEvent.Value            -> onValue(event)
            is BleCentralEvent.DescriptorWritten -> onDescriptorWritten(event)
            else                               -> Unit
        }
    }

    private fun onScanResult(event: BleCentralEvent.ScanResult) {
        if (!scanning) return
        val mfBytes = event.advertisementBytes ?: return
        if (mfBytes.size < COMPANY_ID_BYTES + 6) return
        val companyId = (mfBytes[0].toInt() and 0xFF) or ((mfBytes[1].toInt() and 0xFF) shl 8)
        if (companyId != MANUFACTURER_ID) return
        val sn = try {
            ManufacturerData(mfBytes.copyOfRange(COMPANY_ID_BYTES, mfBytes.size)).getDeviceSN()
        } catch (e: Exception) {
            return
        }
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
        synchronized(readLock) {
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
