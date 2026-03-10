package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.utils.getUnsignedShort

class StatusV2(val command: PumpCommand): PumpObjectModel() {
    /** Pump-calculated absolute insulin, in 0.025U steps */
    val absoluteInsulin get() = getUnsignedShort(command.objectData, 2)

    /** Alarm length */
    val alarmLength get() = AlarmLength.entries.find { it.raw == command.objectData[4] }

    /** Pump battery voltage */
    val batteryVoltage get() = command.objectData[5].toUByte().toDouble() / 100.0

    // TODO: audio bolus settings

    override fun validate(): String? {
        if (alarmLength == null) return "alarmLength invalid"
        return null
    }
}
