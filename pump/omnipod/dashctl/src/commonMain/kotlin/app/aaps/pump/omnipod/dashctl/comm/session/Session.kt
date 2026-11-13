package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.dashctl.comm.exceptions.CouldNotParseResponseException
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
 * - parseResponse does not check the unique id, the length, the sequence number or the CRC of the
 *   pod answer (the Android TODOs are kept).
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

    fun sendCommand(cmd: Command): CommandSendResult {
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

        val response = parseResponse(decrypted)

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
        if (sendResult !is MessageSendSuccess) {
            return CommandAckError(response, "Could not ACK the response: $sendResult")
        }
        return CommandReceiveSuccess(response)
    }

    @Throws(CouldNotParseResponseException::class, UnsupportedOperationException::class)
    private fun parseResponse(decrypted: MessagePacket): Response {

        val data = parseKeys(arrayOf(RESPONSE_PREFIX), decrypted.payload)[0]
        aapsLogger.info(LTag.PUMPBTCOMM, "Received decrypted response: ${data.toCommLogHex()} in packet: $decrypted")

        // TODO verify length

        // val uniqueId = data.copyOfRange(0, 4)
        // val lenghtAndSequenceNumber = data.copyOfRange(4, 6)
        val payload = data.copyOfRange(6, data.size - 2)
        // val crc = data.copyOfRange(data.size - 2, data.size)

        // TODO validate uniqueId, sequenceNumber and crc

        return ResponseUtil.parseResponse(payload)
    }

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
    }
}
