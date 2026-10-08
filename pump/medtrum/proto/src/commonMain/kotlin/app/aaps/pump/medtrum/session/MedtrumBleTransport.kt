package app.aaps.pump.medtrum.session

/**
 * BLE transport seam for the Medtrum session.
 *
 * Pure-Kotlin mirror of the Android `MedtrumBleTransport` /
 * `MedtrumBleCallback` (pump/medtrum). The Android driver implements this
 * over `android.bluetooth`; the iOS driver (M3) over CoreBluetooth.
 * M2 only defines the interface — the transport itself is not implemented here.
 */
interface MedtrumBleCallback {

    fun onConnected()
    fun onDisconnected()

    /** Pump-initiated notification (state updates), outside any command. */
    fun onNotification(data: ByteArray)

    /** Command response to a previous [MedtrumBleTransport.sendMessage]. */
    fun onIndication(data: ByteArray)

    fun onSendMessageError(reason: String, isRetryAble: Boolean)
}

/**
 * Medtrum BLE transport.
 *
 * Higher-level than the generic `BleTransport`: the Medtrum pump needs
 * connect-by-serial, chunked writes with sequence numbers, and Medtrum-specific
 * callbacks. M3 provides the platform implementations.
 */
interface MedtrumBleTransport {

    /** Connect to the pump with the given serial number. Returns true if the attempt started. */
    fun connect(from: String, deviceSN: Long): Boolean

    fun disconnect(from: String)

    /** Send one (already framed) message; the response arrives via [MedtrumBleCallback.onIndication]. */
    fun sendMessage(message: ByteArray)

    fun setCallback(callback: MedtrumBleCallback?)
}
