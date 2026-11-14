package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.dashctl.comm.exceptions.CouldNotParseResponseException
import app.aaps.pump.omnipod.dashctl.comm.exceptions.PodAnswerMismatchException
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendErrorConfirming
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendErrorSending
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.message.StringLengthPrefixEncoding
import app.aaps.pump.omnipod.dashctl.comm.message.StringLengthPrefixEncoding.Companion.parseKeys
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.Command
import app.aaps.pump.omnipod.dashctl.pod.response.Response

/*
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.session.Session`, on the
 * ported MessageIO, MessagePacket and EnDecrypt, with the dashctl pod commands and responses.
 * The only change: `toHex()` is replaced by `toCommLogHex()` (same text, log only).
 *
 * Android behaviour kept as is:
 * - sendCommand tries MAX_TRIES + 1 = 5 times (`0..MAX_TRIES`), and only on MessageSendErrorSending.
 *   The same encrypted message is sent again each time, so the nonce does not move on a retry;
 * - readAndAckResponse also reads up to 5 times. A pod answer that cannot be decrypted or parsed
 *   throws to the caller (DashCryptoException, CouldNotParseResponseException,
 *   UnsupportedOperationException, MessageIOException), and then no ACK is sent;
 * - parseResponse does not check the length or the CRC of the pod answer (the Android TODOs are kept;
 *   OmnipodKit does not check the CRC of a Dash answer either, the pod CRC is not understood).
 *
 * Deliberate difference from Android (insulin counted wrong otherwise): readAndAckResponse checks the
 * 4-bit message sequence number and the unique id of the answer against the command just sent (see
 * [Session.readAndAckResponse]).
 */

sealed class CommandSendResult
object CommandSendSuccess : CommandSendResult()
data class CommandSendErrorSending(val msg: String) : CommandSendResult()

// This error marks the undefined state
data class CommandSendErrorConfirming(val msg: String) : CommandSendResult()

sealed class CommandReceiveResult
data class CommandReceiveSuccess(val result: Response) : CommandReceiveResult()
data class CommandReceiveError(val msg: String) : CommandReceiveResult()
data class CommandAckError(val result: Response, val msg: String) : CommandReceiveResult()

/**
 * An open, encrypted session with the pod: sends one pod command wrapped in `S0.0=...,G0.0`, and
 * reads the pod answer `0.0=...` and ACKs it.
 *
 * [sessionKeys] holds the outer message counter (`msgSequenceNumber`, a full byte). It goes up
 * by one for each command and by one for each ACK, as on Android.
 */
class Session(
    private val aapsLogger: AAPSLogger,
    private val msgIO: MessageIO,
    private val ids: Ids,
    val sessionKeys: SessionKeys,
    val enDecrypt: EnDecrypt
) {

    /** The unique id and the 4-bit sequence of the last command given to [sendCommand]. Null before the first one. */
    private var lastCommandHeader: ByteArray? = null

    fun sendCommand(cmd: Command): CommandSendResult {
        lastCommandHeader = cmd.encoded.copyOfRange(0, COMMAND_HEADER_SIZE)
        sessionKeys.msgSequenceNumber++
        aapsLogger.debug(LTag.PUMPBTCOMM, "Sending command: ${cmd.encoded.toCommLogHex()} in packet $cmd")

        val msg = getCmdMessage(cmd)
        for (i in 0..MAX_TRIES) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Sending command(wrapped): ${msg.payload.toCommLogHex()}")

            when (val sendResult = msgIO.sendMessage(msg)) {
                is MessageSendSuccess         ->
                    return CommandSendSuccess

                is MessageSendErrorConfirming -> {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Error confirming command: $sendResult")
                    return CommandSendErrorConfirming(sendResult.msg)
                }

                is MessageSendErrorSending    ->
                    aapsLogger.debug(LTag.PUMPBTCOMM, "Error sending command: $sendResult")
            }
        }

        val errMsg = "Maximum number of tries reached. Could not send command"
        return CommandSendErrorSending(errMsg)
    }

    /**
     * Reads the pod answer to the last command and ACKs it, as Android.
     *
     * Deliberate difference from Android: after the ACK, the answer must carry the 4-bit message
     * sequence number of the last command plus one, and the unique id of that command (not checked
     * for a command to the unique id ffffffff, the GetVersion and SetUniqueId of the activation).
     * Otherwise [PodAnswerMismatchException] is thrown, so the caller treats it as no answer. Why: a
     * late answer to an earlier command, read as the answer to this one, would decide this command
     * (a bolus counted as given, or as not given) from the wrong status, and insulin would be counted
     * wrong. Android leaves this as a TODO. OmnipodKit (Trio) `BleMessageTransport.readAndAckResponse`
     * checks the sequence the same way, after its ACK, and throws `MessageError.invalidSequence`.
     */
    @Suppress("ReturnCount")
    fun readAndAckResponse(): CommandReceiveResult {
        var responseMsgPacket: MessagePacket? = null
        for (i in 0..MAX_TRIES) {
            val responseMsg = msgIO.receiveMessage()
            if (responseMsg != null) {
                responseMsgPacket = responseMsg
                break
            }
            aapsLogger.debug(LTag.PUMPBTCOMM, "Error receiving response: $responseMsg")
        }

        responseMsgPacket
            ?: return CommandReceiveError("Could not read response")

        val decrypted = enDecrypt.decrypt(responseMsgPacket)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Received response: $decrypted")

        val data = responseData(decrypted)
        val response = ResponseUtil.parseResponse(data.copyOfRange(COMMAND_HEADER_SIZE, data.size - 2))

        /*if (!responseType.isInstance(response)) {
            if (response is AlarmStatusResponse) {
                throw PodAlarmException(response)
            }
            if (response is NakResponse) {
                throw NakResponseException(response)
            }
            throw IllegalResponseException(responseType, response)
        }

         */

        sessionKeys.msgSequenceNumber++
        val ack = getAck(responseMsgPacket)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Sending ACK: ${ack.payload.toCommLogHex()} in packet $ack")
        val sendResult = msgIO.sendMessage(ack)
        checkAnswerBelongsToLastCommand(data)
        if (sendResult !is MessageSendSuccess) {
            return CommandAckError(response, "Could not ACK the response: $sendResult")
        }
        return CommandReceiveSuccess(response)
    }

    /** The pod message inside `0.0=`: unique id, sequence and length, response, CRC. */
    @Throws(CouldNotParseResponseException::class)
    private fun responseData(decrypted: MessagePacket): ByteArray {
        val data = parseKeys(arrayOf(RESPONSE_PREFIX), decrypted.payload)[0]
        aapsLogger.info(LTag.PUMPBTCOMM, "Received decrypted response: ${data.toCommLogHex()} in packet: $decrypted")

        // TODO verify length (Android TODO, kept)
        // The CRC is not checked: Android leaves it as a TODO, and OmnipodKit says the Dash pod CRC
        // does not match the known CRC16, so the Dash PDM ignores it too. An answer too short to hold
        // the header and a response fails in the parse that follows, as on Android.
        return data
    }

    /** See [readAndAckResponse]. */
    @Throws(PodAnswerMismatchException::class)
    private fun checkAnswerBelongsToLastCommand(data: ByteArray) {
        val command = lastCommandHeader
            ?: throw PodAnswerMismatchException("Pod answer with no command sent: ${data.toCommLogHex()}")
        val expectedSequence = (sequenceOf(command) + 1) and 0x0f
        val sequence = sequenceOf(data)
        if (sequence != expectedSequence) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Pod answer with message sequence $sequence, expected $expectedSequence: not used")
            throw PodAnswerMismatchException("Pod answer with message sequence $sequence, expected $expectedSequence")
        }
        val commandId = command.copyOfRange(0, UNIQUE_ID_SIZE)
        val answerId = data.copyOfRange(0, UNIQUE_ID_SIZE)
        val toAnyPod = commandId.all { it == 0xff.toByte() }
        if (!toAnyPod && !answerId.contentEquals(commandId)) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Pod answer for unique id ${answerId.toCommLogHex()}, expected ${commandId.toCommLogHex()}: not used")
            throw PodAnswerMismatchException("Pod answer for unique id ${answerId.toCommLogHex()}, expected ${commandId.toCommLogHex()}")
        }
    }

    /** The 4-bit message sequence of a pod message header (byte 4, bits 2 to 5), as `HeaderEnabledCommand.encodeHeader`. */
    private fun sequenceOf(header: ByteArray): Int = (header[UNIQUE_ID_SIZE].toInt() and 0xff shr 2) and 0x0f

    private fun getAck(response: MessagePacket): MessagePacket {
        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            sequenceNumber = sessionKeys.msgSequenceNumber,
            source = ids.myId,
            destination = ids.podId,
            payload = ByteArray(0),
            eqos = 0,
            ack = true,
            ackNumber = response.sequenceNumber.inc()
        )
        return enDecrypt.encrypt((msg))
    }

    private fun getCmdMessage(cmd: Command): MessagePacket {
        val wrapped = StringLengthPrefixEncoding.formatKeys(
            arrayOf(COMMAND_PREFIX, COMMAND_SUFFIX),
            arrayOf(cmd.encoded, ByteArray(0))
        )

        aapsLogger.debug(LTag.PUMPBTCOMM, "Sending command: ${wrapped.toCommLogHex()}")

        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            sequenceNumber = sessionKeys.msgSequenceNumber,
            source = ids.myId,
            destination = ids.podId,
            payload = wrapped,
            eqos = 1
        )

        return enDecrypt.encrypt(msg)
    }

    companion object {

        private const val COMMAND_PREFIX = "S0.0="
        private const val COMMAND_SUFFIX = ",G0.0"
        private const val RESPONSE_PREFIX = "0.0="

        private const val MAX_TRIES = 4

        private const val UNIQUE_ID_SIZE = 4

        /** Unique id (4 bytes), then sequence and length (2 bytes). */
        private const val COMMAND_HEADER_SIZE = 6
    }
}
