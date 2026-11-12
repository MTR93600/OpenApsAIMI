package app.aaps.pump.omnipod.dashctl.comm.command

import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex
import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

/*
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.command.BleCommand`.
 * The bytes are the same. The only change: `java.nio.ByteBuffer` is replaced by
 * PodByteBuffer (big-endian, like ByteBuffer), and `toHex()` from core:utils by
 * `toCommLogHex()`, which gives the same text (lowercase, no separator).
 */

object BleCommandRTS : BleCommand(BleCommandType.RTS)

object BleCommandCTS : BleCommand(BleCommandType.CTS)

object BleCommandAbort : BleCommand(BleCommandType.ABORT)

object BleCommandSuccess : BleCommand(BleCommandType.SUCCESS)

object BleCommandFail : BleCommand(BleCommandType.FAIL)

data class BleCommandNack(val idx: Byte) : BleCommand(BleCommandType.NACK, byteArrayOf(idx)) {
    companion object {

        fun parse(payload: ByteArray): BleCommand {
            return when {
                payload.size < 2                        ->
                    BleCommandIncorrect("Incorrect NACK payload", payload)

                payload[0] != BleCommandType.NACK.value ->
                    BleCommandIncorrect("Incorrect NACK header", payload)

                else                                    ->
                    BleCommandNack(payload[1])
            }
        }
    }
}

data class BleCommandHello(private val controllerId: Int) : BleCommand(
    BleCommandType.HELLO,
    PodByteBuffer.allocate(6)
        .put(1.toByte()) // TODO find the meaning of this constant
        .put(4.toByte()) // TODO find the meaning of this constant
        .putInt(controllerId).array()
)

/**
 * Note, same as Android: [payload] is NOT part of [data]. [data] is only the INCORRECT
 * type byte.
 */
data class BleCommandIncorrect(val msg: String, val payload: ByteArray) : BleCommand(BleCommandType.INCORRECT)

sealed class BleCommand(val data: ByteArray) {

    constructor(type: BleCommandType) : this(byteArrayOf(type.value))

    constructor(type: BleCommandType, payload: ByteArray) : this(
        byteArrayOf(type.value) + payload
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleCommand) return false

        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun toString(): String {
        return "Raw command: [${data.toCommLogHex()}]"
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }

    companion object {

        fun parse(payload: ByteArray): BleCommand {
            if (payload.isEmpty()) {
                return BleCommandIncorrect("Incorrect command: empty payload", payload)
            }

            return try {
                when (BleCommandType.byValue(payload[0])) {
                    BleCommandType.RTS       ->
                        BleCommandRTS

                    BleCommandType.CTS       ->
                        BleCommandCTS

                    BleCommandType.NACK      ->
                        BleCommandNack.parse(payload)

                    BleCommandType.ABORT     ->
                        BleCommandAbort

                    BleCommandType.SUCCESS   ->
                        BleCommandSuccess

                    BleCommandType.FAIL      ->
                        BleCommandFail

                    BleCommandType.HELLO     ->
                        BleCommandIncorrect("Incorrect hello command received", payload)

                    BleCommandType.INCORRECT ->
                        BleCommandIncorrect("Incorrect command received", payload)
                }
            } catch (e: IllegalArgumentException) {
                BleCommandIncorrect("Incorrect command payload", payload)
            }
        }
    }
}
