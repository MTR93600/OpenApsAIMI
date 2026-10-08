package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange

/** Result of a completed pairing: the LTK and the final message sequence number. */
data class DashPairResult(val ltk: ByteArray, val msgSeq: Int) {

    init {
        require(ltk.size == DashPodState.LTK_SIZE) { "LTK has to be ${DashPodState.LTK_SIZE} bytes long" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DashPairResult) return false
        return ltk.contentEquals(other.ltk) && msgSeq == other.msgSeq
    }

    override fun hashCode(): Int = 31 * ltk.contentHashCode() + msgSeq
}

/**
 * Dash pairing orchestration (LTK exchange).
 *
 * Ported from the Android `LTKExchanger.negotiateLTK`, with the key derivation
 * behind [DashKeyExchange] (D2) and the transport behind [DashMessageTransport]:
 * 1. Send SP1+SP2 (pod address + a GetPodStatus command as SP2 payload).
 * 2. Send SPS1 (PDM public key + PDM nonce).
 * 3. Receive the pod's SPS1 (pod public key + pod nonce) -> derive keys.
 * 4. Send SPS2 (PDM confirmation).
 * 5. Receive the pod's SPS2 and validate its confirmation.
 * 6. Send SP0GP0 (best effort) and read the P0 ack (optional).
 *
 * Pairing messages use the minimal [DashKeyValueCodec] framing; the real
 * `PairMessage`/`MessagePacket` BLE envelope (sequence numbers, addresses,
 * message type, MTU fragmentation) is out of scope for D3.
 *
 * Like the original, sending SP1+SP2, SPS1 or SPS2 must succeed (throws
 * [DashSessionException]), while SP0GP0 is best-effort: after the pod
 * confirmation the pod may already have stored the LTK. [DashCryptoException]
 * from the key exchange propagates unchanged.
 */
class DashPairing(
    private val transport: DashMessageTransport,
    private val keyExchange: DashKeyExchange,
    /** 4-byte pod address, sent as the SP1 payload. */
    private val podAddress: ByteArray,
    /** SP2 payload: a GetPodStatus command, like the original. */
    private val sp2Payload: ByteArray = GET_POD_STATUS_COMMAND
) {

    init {
        require(podAddress.size == POD_ADDRESS_SIZE) { "Pod address has to be $POD_ADDRESS_SIZE bytes long" }
    }

    @Throws(DashSessionException::class, DashCryptoException::class, DashCodecException::class)
    fun negotiateLtk(): DashPairResult {
        var seq = 0

        seq++
        sendOrThrow(
            DashKeyValueCodec.formatKeys(arrayOf(SP1, SP2), arrayOf(podAddress, sp2Payload)),
            SP1 + SP2
        )

        seq++
        sendOrThrow(
            DashKeyValueCodec.formatKeys(
                arrayOf(SPS1),
                arrayOf(keyExchange.pdmPublic + keyExchange.pdmNonce)
            ),
            SPS1
        )

        val podSps1 = transport.receive()
            ?: throw DashSessionException("Could not read SPS1")
        keyExchange.updatePodPublicData(DashKeyValueCodec.parseKeys(arrayOf(SPS1), podSps1)[0])
        // All keys (ltk, pdmConf, podConf) are derived now.

        seq++
        sendOrThrow(
            DashKeyValueCodec.formatKeys(arrayOf(SPS2), arrayOf(keyExchange.pdmConf)),
            SPS2
        )

        val podSps2 = transport.receive()
            ?: throw DashSessionException("Could not read SPS2")
        val podConf = DashKeyValueCodec.parseKeys(arrayOf(SPS2), podSps2)[0]
        if (podConf.size != DashKeyExchange.CMAC_SIZE) {
            throw DashSessionException("Invalid SPS2 payload size: ${podConf.size}")
        }
        keyExchange.validatePodConf(podConf)

        // From here on, throwing no longer means the pairing failed: the pod may
        // already have saved the LTK. (The P0 ack read below can still throw on
        // a malformed P0 via parseKeys; that failure only concerns the optional
        // ack, not the pairing result.)
        seq++
        transport.send(SP0GP0.encodeToByteArray())

        transport.receive()?.let { p0 ->
            val payload = DashKeyValueCodec.parseKeys(arrayOf(P0), p0)[0]
            if (!payload.contentEquals(P0_EXPECTED_PAYLOAD)) {
                // The original only logs a warning here; there is no logger in
                // commonMain, and a bad P0 is not fatal. Deliberately ignored.
            }
        }

        return DashPairResult(ltk = keyExchange.ltk.copyOf(), msgSeq = seq)
    }

    private fun sendOrThrow(data: ByteArray, what: String) {
        if (!transport.send(data)) {
            throw DashSessionException("Could not send $what")
        }
    }

    companion object {
        const val SP1 = "SP1="
        const val SP2 = ",SP2="
        const val SPS1 = "SPS1="
        const val SPS2 = "SPS2="
        const val SP0GP0 = "SP0,GP0"
        const val P0 = "P0="

        const val POD_ADDRESS_SIZE = 4

        /**
         * SP2 payload used by the original: a GetPodStatus command
         * (page 0 parameter), hex "ffc32dbd08030e0100008a".
         */
        val GET_POD_STATUS_COMMAND: ByteArray = byteArrayOf(
            0xff.toByte(), 0xc3.toByte(), 0x2d.toByte(), 0xbd.toByte(), 0x08,
            0x03, 0x0e, 0x01, 0x00, 0x00, 0x8a.toByte()
        )

        private val P0_EXPECTED_PAYLOAD = byteArrayOf(0xa5.toByte())
    }
}
