package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.exceptions.MessageIOException
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex

/*
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.session.EapMessage`.
 * Only change: `java.nio.ByteBuffer` in toByteArray is replaced by a plain byte array with the
 * same big-endian layout, and `toHex()` by `toCommLogHex()` (same text).
 *
 * Android behaviour kept as is: in parse, the total size is `(payload[2] shl 8) or payload[3]`
 * with both bytes signed. A low byte of 0x80 or more makes the size negative. The pod messages
 * seen so far are shorter than 128 bytes, so this does not happen in real traffic.
 */

@Suppress("unused")
enum class EapCode(val code: Byte) {

    REQUEST(1),
    RESPONSE(2),
    SUCCESS(3),
    FAILURE(4);

    companion object {

        fun byValue(value: Byte): EapCode =
            EapCode.entries.firstOrNull { it.code == value }
                ?: throw IllegalArgumentException("Unknown EAP-AKA attribute type: $value")
    }
}

class EapMessage(
    val code: EapCode,
    val identifier: Byte,
    val subType: Byte = 0,
    val attributes: Array<EapAkaAttribute>
) {

    fun toByteArray(): ByteArray {

        val joinedAttributes = attributes.flatMap { it.toByteArray().asIterable() }.toByteArray()

        val attrSize = joinedAttributes.size
        if (attrSize == 0) {
            return byteArrayOf(code.code, identifier, 0, 4)
        }
        val totalSize = HEADER_SIZE + attrSize

        // Same bytes as Android's ByteBuffer: code, identifier, size (big-endian, 2 bytes),
        // AKA packet type, subtype, 2 reserved bytes, then the attributes.
        val header = byteArrayOf(
            code.code,
            identifier,
            ((totalSize ushr 8) and 0XFF).toByte(),
            (totalSize and 0XFF).toByte(),
            AKA_PACKET_TYPE,
            SUBTYPE_AKA_CHALLENGE,
            0,
            0
        )
        return header + joinedAttributes
    }

    companion object {

        private const val HEADER_SIZE = 8
        private const val SUBTYPE_AKA_CHALLENGE = 1.toByte()
        const val SUBTYPE_SYNCHRONIZATION_FAILURE = 4.toByte()

        private const val AKA_PACKET_TYPE = 0x17.toByte()

        fun parse(aapsLogger: AAPSLogger, payload: ByteArray): EapMessage {
            payload.assertSizeAtLeast(4)

            // Same as Android: both bytes signed, see the file comment.
            val totalSize = (payload[2].toInt() shl 8) or payload[3].toInt()
            payload.assertSizeAtLeast(totalSize)

            if (payload.size == 4) { // SUCCESS/FAILURE
                return EapMessage(
                    code = EapCode.byValue(payload[0]),
                    identifier = payload[1],
                    attributes = arrayOf()
                )
            }
            if (totalSize > 0 && payload[4] != AKA_PACKET_TYPE) {
                throw MessageIOException("Invalid eap payload. Expected AKA packet type: ${payload.toCommLogHex()}")
            }
            val attributesPayload = payload.copyOfRange(8, totalSize)
            aapsLogger.debug(LTag.PUMPBTCOMM, "parsing EAP payload: ${payload.toCommLogHex()}")
            return EapMessage(
                code = EapCode.byValue(payload[0]),
                identifier = payload[1],
                attributes = EapAkaAttribute.parseAttributes(attributesPayload).toTypedArray(),
                subType = payload[5],
            )
        }
    }
}

private fun ByteArray.assertSizeAtLeast(size: Int) {
    if (this.size < size) {
        throw MessageIOException("Payload too short: ${this.toCommLogHex()}")
    }
}
