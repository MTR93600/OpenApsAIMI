package app.aaps.pump.omnipod.dashctl.comm.message

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommand
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandAbort
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandCTS
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandFail
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandNack
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandRTS
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandSuccess
import app.aaps.pump.omnipod.dashctl.comm.io.BleConfirmError
import app.aaps.pump.omnipod.dashctl.comm.io.BleConfirmIncorrectData
import app.aaps.pump.omnipod.dashctl.comm.io.BleConfirmSuccess
import app.aaps.pump.omnipod.dashctl.comm.io.BleSendErrorConfirming
import app.aaps.pump.omnipod.dashctl.comm.io.BleSendErrorSending
import app.aaps.pump.omnipod.dashctl.comm.io.BleSendResult
import app.aaps.pump.omnipod.dashctl.comm.io.BleSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.io.CmdBleIO
import app.aaps.pump.omnipod.dashctl.comm.io.DataBleIO
import app.aaps.pump.omnipod.dashctl.comm.packet.BlePacket
import app.aaps.pump.omnipod.dashctl.comm.packet.PayloadJoiner
import app.aaps.pump.omnipod.dashctl.comm.packet.PayloadSplitter
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex

/*
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.message.MessageIO`.
 *
 * The flow on the CMD and DATA characteristics is the same as Android, step by step: same
 * order, same retries, same timeouts, same bytes. The only change: `toHex()` from core:utils is
 * replaced by `toCommLogHex()`, which gives the same text. It is used in log text only.
 *
 * Android behaviour kept as is (it looks odd, but it is what the Android driver does):
 * - In sendMessage, an RTS write that ends with BleSendErrorConfirming is not an error: the
 *   code goes on and waits for CTS. Only BleSendErrorSending stops it.
 * - In peekForNack, a NACK makes the code resend the packet once. It does not look again for a
 *   second NACK. A NACK index outside the list of packets throws IndexOutOfBoundsException.
 * - In receiveMessage, the SUCCESS write result is not checked, and MessagePacket.parse can
 *   throw CouldNotParseMessageException to the caller. FirstBlePacket.parse can also throw
 *   IllegalArgumentException (too many fragments) to the caller: only IncorrectPacketException
 *   and CrcMismatchException are caught.
 */

sealed class MessageSendResult
object MessageSendSuccess : MessageSendResult()
data class MessageSendErrorSending(val msg: String, val cause: Throwable? = null) : MessageSendResult() {
    constructor(e: BleSendResult) : this("Could not send packet: $e")
}

data class MessageSendErrorConfirming(val msg: String, val cause: Throwable? = null) : MessageSendResult() {
    constructor(e: BleSendResult) : this("Could not confirm packet: $e")
}

sealed class PacketReceiveResult
data class PacketReceiveSuccess(val payload: ByteArray) : PacketReceiveResult()
data class PacketReceiveError(val msg: String) : PacketReceiveResult()

/**
 * Sends and receives one whole message over the two pod characteristics.
 *
 * Sending: RTS on CMD, wait for CTS, send each DATA packet (answer a NACK by sending that packet
 * again), then wait for SUCCESS on CMD.
 * Receiving: wait for RTS on CMD, answer CTS, read the DATA packets (send NACK for a missing
 * packet), then answer SUCCESS, or ABORT for a bad packet, or FAIL for a bad CRC.
 *
 * Every call blocks the calling thread (see `BleCharacteristicIO`). Not thread safe: one
 * message at a time, like on Android.
 */
class MessageIO(
    private val aapsLogger: AAPSLogger,
    private val cmdBleIO: CmdBleIO,
    private val dataBleIO: DataBleIO,
) {

    private val receivedOutOfOrder = LinkedHashMap<Byte, ByteArray>()
    var maxMessageReadTries = 3
    var messageReadTries = 0

    @Suppress("ReturnCount")
    fun sendMessage(msg: MessagePacket): MessageSendResult {
        val foundRTS = cmdBleIO.flushIncomingQueue()
        if (foundRTS) {
            val receivedMessage = receiveMessage(false)
            aapsLogger.warn(LTag.PUMPBTCOMM, "sendMessage received message=$receivedMessage")
            throw IllegalStateException("Received message while trying to send")
        }
        dataBleIO.flushIncomingQueue()

        val rtsSendResult = cmdBleIO.sendAndConfirmPacket(BleCommandRTS.data)
        if (rtsSendResult is BleSendErrorSending) {
            return MessageSendErrorSending(rtsSendResult)
        }
        val expectCTS = cmdBleIO.expectCommandType(BleCommandCTS)
        if (expectCTS !is BleConfirmSuccess) {
            return MessageSendErrorSending(expectCTS.toString())
        }

        val payload = msg.asByteArray()
        aapsLogger.debug(LTag.PUMPBTCOMM, "Sending message: ${payload.toCommLogHex()}")
        val splitter = PayloadSplitter(payload)
        val packets = splitter.splitInPackets()

        for ((index, packet) in packets.withIndex()) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Sending DATA: ${packet.toByteArray().toCommLogHex()}")
            val sendResult = dataBleIO.sendAndConfirmPacket(packet.toByteArray())
            val ret = handleSendResult(sendResult, index, packets)
            if (ret !is MessageSendSuccess) {
                return ret
            }
            val peek = peekForNack(index, packets)
            if (peek !is MessageSendSuccess) {
                return if (index == packets.size - 1)
                    MessageSendErrorConfirming(peek.toString())
                else
                    MessageSendErrorSending(peek.toString())
            }
        }

        return when (val expectSuccess = cmdBleIO.expectCommandType(BleCommandSuccess)) {
            is BleConfirmSuccess       ->
                MessageSendSuccess

            is BleConfirmError         ->
                MessageSendErrorConfirming("Error reading message confirmation: $expectSuccess")

            is BleConfirmIncorrectData ->
                when (val received = (BleCommand.parse((expectSuccess.payload)))) {
                    is BleCommandFail ->
                        // this can happen if CRC does not match
                        MessageSendErrorSending("Received FAIL after sending message")

                    else              ->
                        MessageSendErrorConfirming("Received confirmation message: $received")
                }
        }
    }

    @Suppress("ReturnCount")
    fun receiveMessage(readRTS: Boolean = true): MessagePacket? {
        if (readRTS) {
            val expectRTS = cmdBleIO.expectCommandType(BleCommandRTS, MESSAGE_READ_TIMEOUT_MS)
            if (expectRTS !is BleConfirmSuccess) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Error reading RTS: $expectRTS")
                return null
            }
        }

        val sendResult = cmdBleIO.sendAndConfirmPacket(BleCommandCTS.data)
        if (sendResult !is BleSendSuccess) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Error sending CTS: $sendResult")
            return null
        }
        readReset()
        var expected: Byte = 0
        try {
            val firstPacket = expectBlePacket(0)
            if (firstPacket !is PacketReceiveSuccess) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Error reading first packet:$firstPacket")
                return null
            }
            val joiner = PayloadJoiner(firstPacket.payload)
            maxMessageReadTries = joiner.fullFragments * 2 + 2
            for (i in 1 until joiner.fullFragments + 1) {
                expected++
                val nackOnTimeout = !joiner.oneExtraPacket && i == joiner.fullFragments // last packet
                val packet = expectBlePacket(expected, nackOnTimeout)
                if (packet !is PacketReceiveSuccess) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Error reading packet:$packet")
                    return null
                }
                joiner.accumulate(packet.payload)
            }
            if (joiner.oneExtraPacket) {
                expected++
                val packet = expectBlePacket(expected, true)
                if (packet !is PacketReceiveSuccess) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Error reading packet:$packet")
                    return null
                }
                joiner.accumulate(packet.payload)
            }
            val fullPayload = joiner.finalize()
            cmdBleIO.sendAndConfirmPacket(BleCommandSuccess.data)
            return MessagePacket.parse(fullPayload)
        } catch (e: IncorrectPacketException) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Received incorrect packet: $e")
            cmdBleIO.sendAndConfirmPacket(BleCommandAbort.data)
            return null
        } catch (e: CrcMismatchException) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "CRC mismatch: $e")
            cmdBleIO.sendAndConfirmPacket(BleCommandFail.data)
            return null
        } finally {
            readReset()
        }
    }

    private fun handleSendResult(sendResult: BleSendResult, index: Int, packets: List<BlePacket>): MessageSendResult {
        return when {
            sendResult is BleSendSuccess                                      ->
                MessageSendSuccess

            index == packets.size - 1 && sendResult is BleSendErrorConfirming ->
                MessageSendErrorConfirming("Error confirming last DATA packet $sendResult")

            else                                                              ->
                MessageSendErrorSending("Error sending DATA: $sendResult")
        }
    }

    private fun peekForNack(index: Int, packets: List<BlePacket>): MessageSendResult {
        val peekCmd = cmdBleIO.peekCommand()
            ?: return MessageSendSuccess

        return when (val receivedCmd = BleCommand.parse(peekCmd)) {
            is BleCommandNack -> {
                // // Consume NACK
                val received = cmdBleIO.receivePacket()
                if (received == null) {
                    MessageSendErrorSending(received.toString())
                } else {
                    val sendResult = dataBleIO.sendAndConfirmPacket(packets[receivedCmd.idx.toInt()].toByteArray())
                    handleSendResult(sendResult, index, packets)
                }
            }

            BleCommandSuccess -> {
                if (index == packets.size - 1)
                    MessageSendSuccess
                else
                    MessageSendErrorSending("Received SUCCESS before sending all the data. $index")
            }

            else              ->
                MessageSendErrorSending("Received unexpected command: ${peekCmd.toCommLogHex()}")
        }
    }

    @Suppress("ReturnCount")
    private fun expectBlePacket(index: Byte, nackOnTimeout: Boolean = false): PacketReceiveResult {
        receivedOutOfOrder[index]?.let {
            return PacketReceiveSuccess(it)
        }
        var packetTries = 0
        while (messageReadTries < maxMessageReadTries && packetTries < MAX_PACKET_READ_TRIES) {
            messageReadTries++
            packetTries++
            val received = dataBleIO.receivePacket()
            if (received == null || received.isEmpty()) {
                if (nackOnTimeout)
                    cmdBleIO.sendAndConfirmPacket(BleCommandNack(index).data)
                aapsLogger.info(
                    LTag.PUMPBTCOMM,
                    "Error reading index: $index. Received: $received. NackOnTimeout: " +
                        "$nackOnTimeout"
                )
                continue
            }
            if (received[0] == index) {
                return PacketReceiveSuccess(received)
            }
            receivedOutOfOrder[received[0]] = received
            cmdBleIO.sendAndConfirmPacket(BleCommandNack(index).data)
        }
        return PacketReceiveError("Reached the maximum number tries to read a packet")
    }

    private fun readReset() {
        maxMessageReadTries = 3
        messageReadTries = 0
        receivedOutOfOrder.clear()
    }

    companion object {

        private const val MAX_PACKET_READ_TRIES = 4
        private const val MESSAGE_READ_TIMEOUT_MS = 5000.toLong()
    }
}
