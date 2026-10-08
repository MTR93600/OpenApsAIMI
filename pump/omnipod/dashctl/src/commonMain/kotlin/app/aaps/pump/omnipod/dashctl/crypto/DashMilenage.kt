package app.aaps.pump.omnipod.dashctl.crypto

/**
 * Milenage (3GPP TS 35.205/206/207/208) for the Dash EAP-AKA session establishment.
 *
 * Ported from the Android `Milenage`; the algorithm is identical, with the single-block
 * AES-ECB encryption behind the [AesEcbBlockCipher] seam and the RAND behind [SecureRandomBytes]
 * (or supplied directly for deterministic tests).
 *
 * Computes: OPC, RES, CK, IK (not exposed by the Android version), AK, MAC-A, MAC-S, AUTN,
 * and the resynchronization values (AK*, SQN from AUTS).
 */
class DashMilenage(
    private val aesEcb: AesEcbBlockCipher,
    private val k: ByteArray,
    val sqn: ByteArray,
    randParam: ByteArray? = null,
    private val randomBytes: SecureRandomBytes? = null,
    val auts: ByteArray = ByteArray(AUTS_SIZE),
    val amf: ByteArray = MILENAGE_AMF.copyOf()
) {

    init {
        require(k.size == KEY_SIZE) { "Milenage key has to be $KEY_SIZE bytes long" }
        require(sqn.size == SQN_SIZE) { "Milenage SQN has to be $SQN_SIZE bytes long" }
        require(auts.size == AUTS_SIZE) { "Milenage AUTS has to be $AUTS_SIZE bytes long" }
        require(amf.size == MILENAGE_AMF.size) { "Milenage AMF has to be ${MILENAGE_AMF.size} bytes long" }
    }

    /** The 16-byte RAND used; generated if not supplied. */
    val rand: ByteArray = randParam ?: run {
        val rb = randomBytes ?: throw IllegalArgumentException("rand or randomBytes required")
        ByteArray(KEY_SIZE).also { rb.nextBytes(KEY_SIZE).copyInto(it) }
    }

    private fun aesEcbEncrypt(block: ByteArray): ByteArray = aesEcb.encryptBlock(k, block)

    private val opc: ByteArray = aesEcbEncrypt(MILENAGE_OP) xor MILENAGE_OP
    private val randOpcEncrypted: ByteArray = aesEcbEncrypt(rand xor opc)
    private val randOpcEncryptedXorOpc: ByteArray = randOpcEncrypted xor opc

    private val resAkInput: ByteArray = randOpcEncryptedXorOpc.copyOfRange(0, KEY_SIZE).also {
        it[15] = (it[15].toInt() xor 1).toByte()
    }
    private val resAk: ByteArray = aesEcbEncrypt(resAkInput) xor opc

    /** 8-byte RES (response). */
    val res: ByteArray = resAk.copyOfRange(8, 16)
    private val ak: ByteArray = resAk.copyOfRange(0, 6)

    private val ckInput = ByteArray(KEY_SIZE).also { input ->
        for (i in 0..15) {
            input[(i + 12) % 16] = randOpcEncryptedXorOpc[i]
        }
        input[15] = (input[15].toInt() xor 2).toByte()
    }

    /** 16-byte CK (cipher key) — becomes the session key. */
    val ck: ByteArray = aesEcbEncrypt(ckInput) xor opc

    private val sqnAmf: ByteArray = sqn + amf + sqn + amf
    private val sqnAmfXorOpc: ByteArray = sqnAmf xor opc
    private val macAInput = ByteArray(KEY_SIZE).also { input ->
        for (i in 0..15) {
            input[(i + 8) % 16] = sqnAmfXorOpc[i]
        }
    }
    private val macAFull: ByteArray = aesEcbEncrypt(macAInput xor randOpcEncrypted) xor opc
    private val macA: ByteArray = macAFull.copyOfRange(0, 8)

    /** 8-byte MAC-S (for resynchronization). */
    val macS: ByteArray = macAFull.copyOfRange(8, 16)

    /** 16-byte AUTN = (SQN xor AK) || AMF || MAC-A. */
    val autn: ByteArray = (ak xor sqn) + amf + macA

    // Resynchronization: AUTS = (SQN xor AK*) || MAC-S
    private val akStarInput = ByteArray(KEY_SIZE).also { input ->
        for (i in 0..15) {
            input[(i + 4) % 16] = randOpcEncryptedXorOpc[i]
        }
        input[15] = (input[15].toInt() xor 8).toByte()
    }
    private val akStarFull: ByteArray = aesEcbEncrypt(akStarInput) xor opc
    private val akStar: ByteArray = akStarFull.copyOfRange(0, 6)

    private val seqXorAkStar: ByteArray = auts.copyOfRange(0, 6)

    /** SQN recovered from AUTS during resynchronization. */
    val synchronizationSqn: ByteArray = akStar xor seqXorAkStar

    /** MAC-S received in AUTS during resynchronization. */
    val receivedMacS: ByteArray = auts.copyOfRange(6, 14)

    companion object {
        const val KEY_SIZE = 16
        const val AUTS_SIZE = 14
        const val SQN_SIZE = 6

        /** AMF for resynchronization. */
        val RESYNC_AMF: ByteArray = byteArrayOf(0x00, 0x00)

        private val MILENAGE_OP = hexToBytes("cdc202d5123e20f62b6d676ac72cb318")
        private val MILENAGE_AMF = hexToBytes("b9b9")

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "Hex string must have even length" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}

private infix fun ByteArray.xor(other: ByteArray): ByteArray {
    require(size == other.size) { "XOR operands must have equal size" }
    val out = ByteArray(size)
    for (i in indices) out[i] = (this[i].toInt() xor other[i].toInt()).toByte()
    return out
}
