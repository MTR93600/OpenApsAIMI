package app.aaps.pump.omnipod.dashctl.session

/**
 * Minimal EAP-AKA framing for the Dash session establishment.
 *
 * Wire format (mirrors the Android `EapMessage` / `EapAkaAttribute` layout):
 * - Message: `code(1) || identifier(1) || totalLength(2, big-endian) ||
 *   0x17 || subType(1) || 0x00 0x00 || attributes...`
 * - Attribute: `type(1) || lengthIn4ByteWords(1) || 0x00 0x00 || payload`
 * - Success: `0x03 || identifier || 0x00 0x04` (4-byte EAP header, no attributes).
 *
 * Attribute types reuse the real EAP-AKA numbers: AT_RAND=1, AT_AUTN=2,
 * AT_RES=3, AT_CUSTOM_IV=126 (the IV attribute is Dash-specific).
 *
 * Out of scope for D3: the resynchronization flow (AUTS), client error codes,
 * and the `MessagePacket` BLE envelope (sequence numbers, addresses) — those
 * belong to D4 / the platform layer.
 */
object DashEapCodec {

    const val CODE_REQUEST: Byte = 1
    const val CODE_RESPONSE: Byte = 2
    const val CODE_SUCCESS: Byte = 3

    const val AKA_PACKET_TYPE: Byte = 0x17
    const val SUBTYPE_AKA_CHALLENGE: Byte = 1

    const val ATTR_RAND = 1
    const val ATTR_AUTN = 2
    const val ATTR_RES = 3
    const val ATTR_CUSTOM_IV = 126

    const val IV_SIZE = 4
    const val AUTN_SIZE = 16
    const val RAND_SIZE = 16
    const val RES_SIZE = 8

    /** Attribute length unit: attribute sizes are multiples of 4 bytes. */
    private const val ATTR_UNIT = 4
    private const val EAP_HEADER_SIZE = 8

    /** Builds the EAP-AKA challenge (AUTN, RAND, controller IV). */
    fun challenge(identifier: Byte, autn: ByteArray, rand: ByteArray, controllerIv: ByteArray): ByteArray {
        require(autn.size == AUTN_SIZE) { "AUTN has to be $AUTN_SIZE bytes long" }
        require(rand.size == RAND_SIZE) { "RAND has to be $RAND_SIZE bytes long" }
        require(controllerIv.size == IV_SIZE) { "IV has to be $IV_SIZE bytes long" }
        val attributes = attribute(ATTR_AUTN, autn) +
            attribute(ATTR_RAND, rand) +
            attribute(ATTR_CUSTOM_IV, controllerIv)
        return eapPacket(CODE_REQUEST, identifier, AKA_PACKET_TYPE, SUBTYPE_AKA_CHALLENGE, attributes)
    }

    /** Parsed EAP-AKA challenge, as the pod (or a test fake) sees it. */
    data class Challenge(
        val identifier: Byte,
        val autn: ByteArray,
        val rand: ByteArray,
        val controllerIv: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Challenge) return false
            return identifier == other.identifier &&
                autn.contentEquals(other.autn) &&
                rand.contentEquals(other.rand) &&
                controllerIv.contentEquals(other.controllerIv)
        }

        override fun hashCode(): Int {
            var result = identifier.toInt()
            result = 31 * result + autn.contentHashCode()
            result = 31 * result + rand.contentHashCode()
            result = 31 * result + controllerIv.contentHashCode()
            return result
        }
    }

    /** Parses an EAP-AKA challenge. Throws [DashCodecException] on malformed input. */
    fun parseChallenge(payload: ByteArray): Challenge {
        val attributes = parseAttributes(payload, CODE_REQUEST)
        return Challenge(
            identifier = payload[1],
            autn = attributes[ATTR_AUTN] ?: throw DashCodecException("Challenge misses AT_AUTN"),
            rand = attributes[ATTR_RAND] ?: throw DashCodecException("Challenge misses AT_RAND"),
            controllerIv = attributes[ATTR_CUSTOM_IV] ?: throw DashCodecException("Challenge misses AT_CUSTOM_IV")
        )
    }

    /** Builds the EAP-AKA challenge response (RES + pod IV). */
    fun response(identifier: Byte, res: ByteArray, nodeIv: ByteArray): ByteArray {
        require(res.size == RES_SIZE) { "RES has to be $RES_SIZE bytes long" }
        require(nodeIv.size == IV_SIZE) { "IV has to be $IV_SIZE bytes long" }
        val attributes = attribute(ATTR_RES, res) + attribute(ATTR_CUSTOM_IV, nodeIv)
        return eapPacket(CODE_RESPONSE, identifier, AKA_PACKET_TYPE, SUBTYPE_AKA_CHALLENGE, attributes)
    }

    /** Parsed EAP-AKA challenge response: RES plus the pod's IV. */
    data class ChallengeResponse(val res: ByteArray, val nodeIv: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ChallengeResponse) return false
            return res.contentEquals(other.res) && nodeIv.contentEquals(other.nodeIv)
        }

        override fun hashCode(): Int = 31 * res.contentHashCode() + nodeIv.contentHashCode()
    }

    /**
     * Parses the pod's challenge response. Checks the EAP code and the
     * identifier (the original `assertIdentifier`), requires AT_RES and
     * AT_CUSTOM_IV, and rejects unknown attribute types (like the original
     * `processChallengeResponse`).
     */
    fun parseResponse(payload: ByteArray, expectedIdentifier: Byte): ChallengeResponse {
        val attributes = parseAttributes(payload, CODE_RESPONSE, expectedIdentifier)
        val res = attributes[ATTR_RES] ?: throw DashCodecException("Response misses AT_RES")
        require(res.size == RES_SIZE) { "AT_RES has to be $RES_SIZE bytes long" }
        val nodeIv = attributes[ATTR_CUSTOM_IV] ?: throw DashCodecException("Response misses AT_CUSTOM_IV")
        require(nodeIv.size == IV_SIZE) { "AT_CUSTOM_IV has to be $IV_SIZE bytes long" }
        return ChallengeResponse(res, nodeIv)
    }

    /** Builds the EAP success message (4-byte header, no attributes). */
    fun success(identifier: Byte): ByteArray =
        byteArrayOf(CODE_SUCCESS, identifier, 0, 4)

    private fun attribute(type: Int, payload: ByteArray): ByteArray {
        require(payload.size % ATTR_UNIT == 0) { "Attribute payload must be a multiple of $ATTR_UNIT bytes" }
        val total = ATTR_UNIT + payload.size
        return byteArrayOf(type.toByte(), (total / ATTR_UNIT).toByte(), 0, 0) + payload
    }

    private fun eapPacket(
        code: Byte,
        identifier: Byte,
        packetType: Byte,
        subType: Byte,
        attributes: ByteArray
    ): ByteArray {
        val total = EAP_HEADER_SIZE + attributes.size
        return byteArrayOf(
            code,
            identifier,
            ((total ushr 8) and 0xff).toByte(),
            (total and 0xff).toByte(),
            packetType,
            subType,
            0,
            0
        ) + attributes
    }

    /**
     * Parses the EAP header and returns the attributes as type -> payload.
     * Unknown attribute types are rejected, mirroring the original's strictness.
     */
    private fun parseAttributes(
        payload: ByteArray,
        expectedCode: Byte,
        expectedIdentifier: Byte? = null
    ): Map<Int, ByteArray> {
        if (payload.size < 4) {
            throw DashCodecException("EAP payload too short")
        }
        if (payload[0] != expectedCode) {
            throw DashCodecException("Unexpected EAP code: ${payload[0]}")
        }
        if (expectedIdentifier != null && payload[1] != expectedIdentifier) {
            throw DashCodecException("EAP identifier mismatch")
        }
        val total = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        if (payload.size < total) {
            throw DashCodecException("EAP payload shorter than declared length")
        }
        if (total == 4) {
            throw DashCodecException("EAP message has no attributes")
        }
        if (payload[4] != AKA_PACKET_TYPE) {
            throw DashCodecException("Not an AKA packet")
        }
        val attributes = mutableMapOf<Int, ByteArray>()
        var pos = EAP_HEADER_SIZE
        while (pos < total) {
            if (pos + ATTR_UNIT > total) {
                throw DashCodecException("Truncated attribute header")
            }
            val type = payload[pos].toInt() and 0xff
            val attrLength = (payload[pos + 1].toInt() and 0xff) * ATTR_UNIT
            if (attrLength < ATTR_UNIT || pos + attrLength > total) {
                throw DashCodecException("Bad attribute length")
            }
            val value = payload.copyOfRange(pos + ATTR_UNIT, pos + attrLength)
            when (type) {
                ATTR_RAND, ATTR_AUTN, ATTR_RES, ATTR_CUSTOM_IV -> attributes[type] = value
                else                                          -> throw DashCodecException("Unknown attribute type: $type")
            }
            pos += attrLength
        }
        return attributes
    }
}
