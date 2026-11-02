package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.getDateTime
import app.aaps.pump.apex.utils.getUnsignedShort
import app.aaps.pump.apex.utils.hexAsDecToDec
import app.aaps.pump.apex.utils.validateDateTime
import org.joda.time.DateTime

class TDDEntry(
    val command: PumpCommand,
    val apexDeviceInfo: ApexDeviceInfo
): PumpObjectModel() {
    /** TDD entry index */
    val index get() = command.objectData[1].toUByte().toInt()

    /** Bolus part of TDD */
    val bolus get() = getUnsignedShort(command.objectData, 2)

    /** Basal part of TDD */
    val basal get() = getUnsignedShort(command.objectData, 4)

    /** Temporary basal part of TDD */
    val temporaryBasal get() = getUnsignedShort(command.objectData, 6)

    /** TDD */
    val total get() = bolus + basal + temporaryBasal

    /** TDD entry date */
    val dateTime get() = getDateTime(command.objectData, 8, apexDeviceInfo, ignoreTime = true)

    override fun validate(): String? {
        if (!validateDateTime(command.objectData, 8, apexDeviceInfo, ignoreTime = true)) return "dateTime invalid"
        return null
    }
}
