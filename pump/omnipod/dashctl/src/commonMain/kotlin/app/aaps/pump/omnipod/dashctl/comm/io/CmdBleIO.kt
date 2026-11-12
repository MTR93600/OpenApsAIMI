package app.aaps.pump.omnipod.dashctl.comm.io

import app.aaps.pump.omnipod.dashctl.comm.command.BleCommand

/**
 * The CMD characteristic ([CharacteristicType.CMD]): the flow-control channel (RTS, CTS, NACK,
 * SUCCESS, FAIL, ABORT, HELLO). Used by `MessageIO`.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.CmdBleIO`.
 * Everything in the [BleCharacteristicIO] contract applies.
 */
interface CmdBleIO : BleCharacteristicIO {

    /**
     * Look at the packet at the head of the CMD incoming queue WITHOUT removing it, and without
     * waiting.
     *
     * Android: `BlockingQueue.peek()`.
     *
     * @return the packet, or null when the queue is empty.
     */
    fun peekCommand(): ByteArray?

    /**
     * Send the HELLO command for the connection handshake.
     *
     * Android sends `BleCommandHello(CONTROLLER_ID).data` with `sendAndConfirmPacket`, where
     * `CONTROLLER_ID` is 4242 (`OmnipodDashBleManagerImpl.CONTROLLER_ID`), so the bytes are
     * `06 01 04 00 00 10 92`.
     *
     * @return the result of [sendAndConfirmPacket].
     */
    fun hello(): BleSendResult

    /**
     * Take the next CMD packet ([receivePacket] with [timeoutMs]) and check that its first byte is
     * the first byte of [expected]. The packet is removed from the queue in every case.
     *
     * @return [BleConfirmSuccess] when the packet is not empty and the first bytes match;
     *  [BleConfirmIncorrectData] with the whole packet when it does not match (an empty packet
     *  also gives this); [BleConfirmError] when nothing came before the timeout.
     */
    fun expectCommandType(expected: BleCommand, timeoutMs: Long = BleCharacteristicIO.DEFAULT_IO_TIMEOUT_MS): BleConfirmResult
}
