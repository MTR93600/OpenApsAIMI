package app.aaps.pump.omnipod.dashctl.session

/**
 * Raw byte transport for pairing and session messages.
 *
 * This is the seam between the protocol logic in commonMain and the BLE message
 * layer. The real BLE framing (MessagePacket assembly, MTU fragmentation,
 * acknowledgements, timeouts) is platform work and out of scope for D3; the
 * platform implementation sends one message per call and returns the next
 * received message.
 */
interface DashMessageTransport {

    /**
     * Sends one complete message. Returns false when the message could not be
     * sent or confirmed.
     */
    fun send(data: ByteArray): Boolean

    /** Receives one complete message, or null when nothing was received. */
    fun receive(): ByteArray?
}
