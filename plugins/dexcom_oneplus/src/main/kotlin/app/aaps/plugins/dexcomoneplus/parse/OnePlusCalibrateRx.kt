package app.aaps.plugins.dexcomoneplus.parse

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the sensor answered to a calibration.
 *
 * There are two shapes, and only one of them can be read.
 *
 * [Form.Status] is the G5/G6 answer xDrip documents: opcode 0x35, 5 bytes `opcode | info | result |
 * crc16`, with a result byte that says plainly whether the value was taken.
 *
 * [Form.Echo] is what a Dexcom ONE+ really sends. The only public field captures of a ONE+ being
 * calibrated show 4 bytes that echo the request opcode — `34 00 01 00` and `34 00 02 00` — with no
 * 0x35 and no CRC that matches this family's trailer. Nothing published says what the third byte
 * means, and the two readings of it point opposite ways: it may be a private status from a sensor
 * that accepted the value, or it may be the firmware refusing an opcode it does not support. The
 * other Control commands of this driver all answer with opcode+1 (0x26 → 0x27), never an echo, so
 * an echo here is not this characteristic's normal shape either.
 *
 * So an echo is reported as [Outcome.UNKNOWN] and never as an acceptance. Telling the user their
 * fingerstick was taken, on a guess, would be worse than telling them we cannot say: a calibration
 * the sensor keeps cannot be edited or deleted afterwards.
 *
 * Provenance: xDrip `CalibrateRxMessage` (GPL-3.0) for the 0x35 shape; xDrip discussion #4034 for
 * the ONE+ capture. See docs/DEXCOM_ONEPLUS_CALIBRATION_TO_SENSOR.md.
 */
data class OnePlusCalibrateRx(
    val info: Int,
    val result: Int,
    val valid: Boolean,
    val form: Form = Form.Status,
    /** The bytes exactly as they arrived, so an unread answer can still be reported and studied. */
    val rawHex: String = "",
) {

    enum class Form {

        /** Opcode 0x35, result byte readable against the code table below. */
        Status,

        /** The 4-byte ONE+ echo of opcode 0x34. Carries a byte we cannot name. */
        Echo
    }

    enum class Outcome {

        /** The sensor said it took the value. */
        ACCEPTED,

        /** The sensor said no, and said why. */
        REFUSED,

        /** Something came back that we cannot read. It may or may not have been taken. */
        UNKNOWN
    }

    fun outcome(): Outcome = when {
        !valid             -> Outcome.UNKNOWN
        form == Form.Echo  -> Outcome.UNKNOWN
        accepted()         -> Outcome.ACCEPTED
        else               -> Outcome.REFUSED
    }

    /**
     * The sensor took the value. [SECOND_NEEDED] and [DUPLICATE] are accepted outcomes too.
     *
     * False for an echo: not a refusal, simply not something this answer lets us claim.
     */
    fun accepted(): Boolean =
        valid && form == Form.Status && (result == OK || result == SECOND_NEEDED || result == DUPLICATE)

    /** The sensor took it and asks for a second fingerstick before it fully trusts itself again. */
    fun wantsSecondCalibration(): Boolean = valid && form == Form.Status && result == SECOND_NEEDED

    fun message(): String = when {
        !valid                  -> "Unable to decode"
        form == Form.Echo       -> "Sensor answered $rawHex — this firmware does not send a readable result"
        result == OK            -> "OK"
        result == 0x01          -> "Code 1"
        result == SECOND_NEEDED -> "Second calibration needed"
        result == REJECTED      -> "Rejected"
        result == STOPPED       -> "Sensor stopped"
        result == DUPLICATE     -> "Duplicate"
        result == NOT_READY     -> "Not ready to calibrate"
        else                    -> "Unknown code: $result"
    }

    companion object {

        const val OPCODE: Byte = 0x35
        const val PACKET_LENGTH: Int = 5

        /** A ONE+ answers by echoing the request opcode instead of using [OPCODE]. */
        const val ECHO_OPCODE: Byte = OnePlusCalibrateTx.OPCODE
        const val ECHO_PACKET_LENGTH: Int = 4

        const val OK = 0x00
        const val SECOND_NEEDED = 0x06
        const val REJECTED = 0x08
        const val STOPPED = 0x0B
        const val DUPLICATE = 0x0D
        const val NOT_READY = 0x0E

        /**
         * Read an answer to a calibration, or null when the packet is something else entirely.
         *
         * Only ever called while a calibration reply is being waited for, so a 0x34-prefixed packet
         * here is an answer to our own request and not a stray command.
         */
        fun parse(packet: ByteArray): OnePlusCalibrateRx? {
            if (packet.size == ECHO_PACKET_LENGTH && packet[0] == ECHO_OPCODE) {
                return OnePlusCalibrateRx(
                    info = packet[1].toInt() and 0xff,
                    result = packet[2].toInt() and 0xff,
                    valid = true,
                    form = Form.Echo,
                    rawHex = packet.toHex(),
                )
            }
            if (packet.size != PACKET_LENGTH) return null
            if (packet[0] != OPCODE) return null
            if (!OnePlusFastCrc16.check(packet)) return null
            val data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            data.get() // opcode
            val info = data.get().toInt() and 0xff
            val result = data.get().toInt() and 0xff
            return OnePlusCalibrateRx(
                info = info,
                result = result,
                valid = true,
                form = Form.Status,
                rawHex = packet.toHex(),
            )
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
