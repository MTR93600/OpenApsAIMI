package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.utils.getUnsignedShort

class CommandResponse(val command: PumpCommand): PumpObjectModel() {
    /** Command response code */
    val code get() = Code.entries.find { it.raw.toByte() == command.objectData[0] } ?: Code.Unknown

    /** Bolus dose if present */
    val dose get() = getUnsignedShort(command.objectData, 2)

    enum class Code(val raw: Int) {
        Accepted(0x55),
        Invalid(0xA5),
        Completed(0xAA),
        StandardBolusProgress(0xA0),
        ExtendedBolusProgress(0xA1),
        Unknown(0xBADC0DE)
    }
}
