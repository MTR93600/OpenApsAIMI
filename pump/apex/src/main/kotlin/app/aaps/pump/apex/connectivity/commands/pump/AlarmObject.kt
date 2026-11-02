package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.getDateTime
import app.aaps.pump.apex.utils.getUnsignedShort
import app.aaps.pump.apex.utils.hexAsDecToDec
import app.aaps.pump.apex.utils.validateDateTime
import org.joda.time.DateTime

class AlarmObject(
    val command: PumpCommand,
    val apexDeviceInfo: ApexDeviceInfo
): PumpObjectModel() {
    /** Alarm entry index */
    val index get() = command.objectData[1].toUByte().toInt()

    /** Alarm date */
    val dateTime get() = getDateTime(command.objectData, 2, apexDeviceInfo)

    /** Alarm type */
    val type get() = Alarm.entries.find { it.raw == (getUnsignedShort(command.objectData, 8) + 0x100) }

    override fun validate(): String? {
        if (type == null) return "type == null"
        if (!validateDateTime(command.objectData, 2, apexDeviceInfo)) return "invalid datetime"
        return null
    }
}