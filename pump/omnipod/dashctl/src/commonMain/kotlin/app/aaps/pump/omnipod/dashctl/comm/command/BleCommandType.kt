package app.aaps.pump.omnipod.dashctl.comm.command

/**
 * The first byte of a packet on the CMD characteristic.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.command.BleCommandType`.
 * The values are the same.
 */
enum class BleCommandType(val value: Byte) {
    RTS(0x00.toByte()),
    CTS(0x01.toByte()),
    NACK(0x02.toByte()),
    ABORT(0x03.toByte()),
    SUCCESS(0x04.toByte()),
    FAIL(0x05.toByte()),
    HELLO(0x06.toByte()),
    INCORRECT(0x09.toByte());

    companion object {

        fun byValue(value: Byte): BleCommandType =
            BleCommandType.entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown BleCommandType: $value")
    }
}
