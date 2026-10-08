package app.aaps.pump.omnipod.dashctl.crypto

/**
 * Dash pairing key exchange: X25519 ECDH followed by AES-CMAC key derivation.
 *
 * Ported from the Android `KeyExchange`; the derivation is identical, with X25519 behind
 * the [X25519Dh] seam, AES-CMAC behind [AesCmac], and randomness behind [SecureRandomBytes].
 *
 * Protocol:
 * 1. PDM generates a nonce and an X25519 key pair, sends public key + nonce (SPS1).
 * 2. Pod replies with its public key + nonce; [updatePodPublicData] derives:
 *    - `curveLTK` = X25519(pdmPrivate, podPublic)
 *    - `intermediateKey` = CMAC(key=firstKey, data=curveLTK) where firstKey = last4(podPublic) || last4(pdmPublic) || last4(podNonce) || last4(pdmNonce)
 *    - `ltk` = CMAC(intermediateKey, 0x02 || "TWIt" || podNonce || pdmNonce || 0x00 0x01)
 *    - `confKey` = CMAC(intermediateKey, 0x01 || "TWIt" || podNonce || pdmNonce || 0x00 0x01)
 *    - `pdmConf` = CMAC(confKey, "KC_2_U" || pdmNonce || podNonce)
 *    - `podConf` = CMAC(confKey, "KC_2_V" || podNonce || pdmNonce)
 * 3. [validatePodConf] checks the pod's confirmation (SPS2).
 *
 * For deterministic tests, [pdmNonce] and [pdmPrivate] can be injected.
 */
class DashKeyExchange(
    private val x25519: X25519Dh,
    private val aesCmac: AesCmac,
    randomBytes: SecureRandomBytes? = null,
    pdmNonce: ByteArray? = null,
    pdmPrivate: ByteArray? = null
) {

    val pdmNonce: ByteArray = pdmNonce ?: run {
        requireNotNull(randomBytes) { "randomBytes or pdmNonce required" }
        randomBytes.nextBytes(NONCE_SIZE)
    }
    val pdmPrivate: ByteArray = pdmPrivate ?: x25519.generatePrivateKey()
    val pdmPublic: ByteArray = x25519.publicFromPrivate(this.pdmPrivate)

    var podPublic: ByteArray = ByteArray(PUBLIC_KEY_SIZE)
        private set
    var podNonce: ByteArray = ByteArray(NONCE_SIZE)
        private set

    val podConf = ByteArray(CMAC_SIZE)
    val pdmConf = ByteArray(CMAC_SIZE)

    var ltk = ByteArray(CMAC_SIZE)
        private set

    init {
        require(this.pdmNonce.size == NONCE_SIZE) { "PDM nonce must be $NONCE_SIZE bytes" }
        require(this.pdmPrivate.size == PUBLIC_KEY_SIZE) { "Private key must be $PUBLIC_KEY_SIZE bytes" }
    }

    /** Processes the pod's SPS1 payload (podPublic || podNonce) and derives all keys. */
    fun updatePodPublicData(payload: ByteArray) {
        if (payload.size != PUBLIC_KEY_SIZE + NONCE_SIZE) {
            throw DashCryptoException("Invalid SPS1 payload size: ${payload.size}")
        }
        podPublic = payload.copyOfRange(0, PUBLIC_KEY_SIZE)
        podNonce = payload.copyOfRange(PUBLIC_KEY_SIZE, PUBLIC_KEY_SIZE + NONCE_SIZE)
        generateKeys()
    }

    /** Validates the pod's SPS2 confirmation against the derived [podConf]. */
    fun validatePodConf(payload: ByteArray) {
        if (!podConf.contentEquals(payload)) {
            throw DashCryptoException("Invalid podConf value received")
        }
    }

    private fun generateKeys() {
        val curveLTK = x25519.computeSharedSecret(pdmPrivate, podPublic)

        val firstKey = podPublic.copyOfRange(podPublic.size - 4, podPublic.size) +
            pdmPublic.copyOfRange(pdmPublic.size - 4, pdmPublic.size) +
            podNonce.copyOfRange(podNonce.size - 4, podNonce.size) +
            pdmNonce.copyOfRange(pdmNonce.size - 4, pdmNonce.size)

        val intermediateKey = aesCmac.compute(firstKey, curveLTK)

        val ltkData = byteArrayOf(2.toByte()) +
            INTERMEDIARY_KEY_MAGIC_STRING +
            podNonce +
            pdmNonce +
            byteArrayOf(0.toByte(), 1.toByte())
        ltk = aesCmac.compute(intermediateKey, ltkData)

        val confData = byteArrayOf(1.toByte()) +
            INTERMEDIARY_KEY_MAGIC_STRING +
            podNonce +
            pdmNonce +
            byteArrayOf(0.toByte(), 1.toByte())
        val confKey = aesCmac.compute(intermediateKey, confData)

        val pdmConfData = PDM_CONF_MAGIC_PREFIX +
            pdmNonce +
            podNonce
        aesCmac.compute(confKey, pdmConfData).copyInto(pdmConf)

        val podConfData = POD_CONF_MAGIC_PREFIX +
            podNonce +
            pdmNonce
        aesCmac.compute(confKey, podConfData).copyInto(podConf)
    }

    companion object {
        const val PUBLIC_KEY_SIZE = 32
        const val NONCE_SIZE = 16
        const val CMAC_SIZE = 16

        private val INTERMEDIARY_KEY_MAGIC_STRING = "TWIt".encodeToByteArray()
        private val PDM_CONF_MAGIC_PREFIX = "KC_2_U".encodeToByteArray()
        private val POD_CONF_MAGIC_PREFIX = "KC_2_V".encodeToByteArray()
    }
}
