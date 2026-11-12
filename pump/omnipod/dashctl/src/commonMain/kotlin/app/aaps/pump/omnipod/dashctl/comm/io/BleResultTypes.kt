package app.aaps.pump.omnipod.dashctl.comm.io

/*
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.interfaces.io.BleResultTypes`.
 * Same classes, same fields.
 */

/** Result of [BleCharacteristicIO.sendAndConfirmPacket] and [BleCharacteristicIO.readyToRead]. */
sealed class BleSendResult

/** The write was done and the BLE stack confirmed it, with the same bytes on the same characteristic. */
object BleSendSuccess : BleSendResult()

/** The write could not be started. Nothing was sent to the pod. */
data class BleSendErrorSending(val msg: String, val cause: Throwable? = null) : BleSendResult()

/**
 * The write was started, but no matching confirmation came in time (timeout, error status, or a
 * confirmation for other bytes or another characteristic). The pod may or may not have the packet.
 */
data class BleSendErrorConfirming(val msg: String, val cause: Throwable? = null) : BleSendResult()

/** Result of [CmdBleIO.expectCommandType]. */
sealed class BleConfirmResult

/** A packet came and its first byte is the expected command type. */
object BleConfirmSuccess : BleConfirmResult()

/** A packet came, but it is not the expected command. [payload] is the whole packet, as received. */
data class BleConfirmIncorrectData(val payload: ByteArray) : BleConfirmResult()

/** No packet came before the timeout. */
data class BleConfirmError(val msg: String) : BleConfirmResult()
