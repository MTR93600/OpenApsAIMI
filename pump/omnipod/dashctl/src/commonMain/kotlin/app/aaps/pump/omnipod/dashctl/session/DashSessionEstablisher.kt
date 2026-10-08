package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.crypto.SecureRandomBytes

/** Session keys from a completed EAP-AKA: the cipher key and the message nonce. */
data class DashSessionKeys(val ck: ByteArray, val nonce: DashNonce) {

    init {
        require(ck.size == CK_SIZE) { "CK has to be $CK_SIZE bytes long" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DashSessionKeys) return false
        return ck.contentEquals(other.ck) && nonce.prefix.contentEquals(other.nonce.prefix)
    }

    override fun hashCode(): Int = 31 * ck.contentHashCode() + nonce.prefix.contentHashCode()

    companion object {
        const val CK_SIZE = 16
    }
}

/**
 * Dash EAP-AKA session establishment.
 *
 * Ported from the Android `SessionEstablisher.negotiateSessionKeys`, with Milenage
 * behind [DashMilenage] (D2) and the transport behind [DashMessageTransport]:
 * 1. Take the LTK from the state manager (throws when unpaired) and increase
 *    the EAP-AKA sequence number (6 big-endian bytes, uncommitted). The 4-bit
 *    BLE message sequence number must have been seeded beforehand with
 *    [DashPodStateManager.seedMessageSequenceNumber] from `pairResult.msgSeq`
 *    after a fresh pairing (D4 does this); the original seeded it by passing
 *    `pairResult.msgSeq` into the session establishment.
 * 2. Increase the message sequence number and send the challenge
 *    (AUTN, RAND, controller IV) — like the original's `msgSeq++` before
 *    the challenge.
 * 3. Receive the response, check the identifier, validate RES, take the pod IV.
 * 4. Increase the message sequence number again and send EAP success
 *    (best effort, like the original) — the original's second `msgSeq++`.
 * 5. Commit the EAP-AKA sequence number and return the session keys; the nonce
 *    prefix is `controllerIV + nodeIV` with sqn 0, exactly like the original.
 *
 * EAP messages use the minimal [DashEapCodec] framing. Out of scope for D3:
 * the resynchronization flow (AUTS) and the `MessagePacket` BLE envelope —
 * those belong to D4 / the platform layer.
 *
 * For deterministic tests, [controllerIv], [rand] and [identifier] can be
 * injected; otherwise they come from [randomBytes].
 */
class DashSessionEstablisher(
    private val transport: DashMessageTransport,
    private val stateManager: DashPodStateManager,
    private val aesEcb: AesEcbBlockCipher,
    private val randomBytes: SecureRandomBytes? = null,
    private val controllerIv: ByteArray? = null,
    private val rand: ByteArray? = null,
    private val identifier: Byte? = null
) {

    init {
        require(controllerIv == null || controllerIv.size == DashEapCodec.IV_SIZE) {
            "Controller IV has to be ${DashEapCodec.IV_SIZE} bytes long"
        }
        require(rand == null || rand.size == DashMilenage.KEY_SIZE) {
            "RAND has to be ${DashMilenage.KEY_SIZE} bytes long"
        }
    }

    @Throws(DashSessionException::class, DashCryptoException::class, DashCodecException::class)
    fun negotiateSessionKeys(): DashSessionKeys {
        val ltk = stateManager.ltk
            ?: throw DashSessionException("Not paired: no LTK stored")

        val eapSqn = stateManager.increaseEapAkaSequenceNumber()
        val milenage = DashMilenage(aesEcb, ltk, eapSqn, randParam = rand, randomBytes = randomBytes)

        val iv = controllerIv ?: randomBytes(DashEapCodec.IV_SIZE)
        val id = identifier ?: randomBytes(1)[0]

        // First message-sequence step, like the original's msgSeq++ before the challenge.
        stateManager.increaseMessageSequenceNumber()
        val challenge = DashEapCodec.challenge(id, milenage.autn, milenage.rand, iv)
        if (!transport.send(challenge)) {
            throw DashSessionException("Could not send the EAP-AKA challenge")
        }

        val rawResponse = transport.receive()
            ?: throw DashSessionException("Could not read the EAP-AKA challenge response")
        val response = DashEapCodec.parseResponse(rawResponse, id)

        if (!milenage.res.contentEquals(response.res)) {
            throw DashSessionException("RES mismatch")
        }

        // Second message-sequence step, like the original's msgSeq++ before the success.
        stateManager.increaseMessageSequenceNumber()
        transport.send(DashEapCodec.success(id))
        stateManager.commitEapAkaSequenceNumber()

        return DashSessionKeys(
            ck = milenage.ck.copyOf(),
            nonce = DashNonce(prefix = iv + response.nodeIv, sqn = 0)
        )
    }

    private fun randomBytes(length: Int): ByteArray {
        val rb = randomBytes
            ?: throw DashSessionException("No random source: inject the value or provide randomBytes")
        return rb.nextBytes(length)
    }
}
