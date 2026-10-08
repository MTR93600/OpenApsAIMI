package app.aaps.pump.omnipod.dashctl.crypto

/**
 * Dash message authenticated encryption orchestration.
 *
 * Ported from the Android `EnDecrypt`; the construction is identical, with AES-CCM behind
 * the [AesCcmCipher] seam. The Android version operated on `MessagePacket` (BLE message layer,
 * out of scope); this works on the raw header/payload byte arrays.
 *
 * - Encryption: nonce = [DashNonce.increment] (podReceiving=true), AAD = 16-byte header,
 *   output = ciphertext + 8-byte MAC appended to the payload.
 * - Decryption: nonce = [DashNonce.increment] (podReceiving=false), AAD = 16-byte header,
 *   output = payload with the 8-byte MAC removed after verification.
 */
class DashEnDecrypt(
    private val aesCcm: AesCcmCipher,
    private val nonce: DashNonce,
    private val ck: ByteArray
) {

    init {
        require(ck.size == CK_SIZE) { "CK has to be $CK_SIZE bytes long" }
    }

    /**
     * Encrypts [payload] with the 16-byte [header] as associated data.
     * Returns the payload with ciphertext + [MAC_SIZE_BYTES]-byte MAC.
     */
    fun encrypt(header: ByteArray, payload: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE) { "Header has to be $HEADER_SIZE bytes long" }
        val n = nonce.increment(podReceiving = true)
        return aesCcm.encrypt(
            key = ck,
            nonce = n,
            associatedData = header,
            plaintext = payload,
            macSizeBytes = MAC_SIZE_BYTES
        )
    }

    /**
     * Decrypts [payloadWithMac] with the 16-byte [header] as associated data.
     * Returns the plaintext payload; throws [DashCryptoException] on authentication failure.
     */
    fun decrypt(header: ByteArray, payloadWithMac: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE) { "Header has to be $HEADER_SIZE bytes long" }
        require(payloadWithMac.size >= MAC_SIZE_BYTES) { "Payload too short to hold a MAC" }
        val n = nonce.increment(podReceiving = false)
        return aesCcm.decrypt(
            key = ck,
            nonce = n,
            associatedData = header,
            ciphertextWithMac = payloadWithMac,
            macSizeBytes = MAC_SIZE_BYTES
        )
    }

    companion object {
        /** MAC size in bytes (64 bits), as used by the pod. */
        const val MAC_SIZE_BYTES = 8

        /** Session key size in bytes. */
        const val CK_SIZE = 16

        /** AAD header size in bytes. */
        const val HEADER_SIZE = 16
    }
}
