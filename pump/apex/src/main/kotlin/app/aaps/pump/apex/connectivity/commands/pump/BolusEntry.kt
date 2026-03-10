package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.R
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.getDateTime
import app.aaps.pump.apex.utils.getUnsignedShort
import app.aaps.pump.apex.utils.hexAsDecToDec
import app.aaps.pump.apex.utils.validateDateTime
import org.joda.time.DateTime

class BolusEntry(
    val command: PumpCommand,
    val info: ApexDeviceInfo
): PumpObjectModel() {
    /** Bolus entry index */
    val index get() = command.objectData[1].toUByte().toInt()

    /** Bolus date */
    val dateTime get() = getDateTime(command.objectData, 2, info)

    /** Standard bolus requested dose */
    val standardDose get() = getUnsignedShort(command.objectData, 8)

    /** Standard bolus actual dose */
    val standardPerformed get() = getUnsignedShort(command.objectData, 10)

    /** Extended bolus requested dose */
    val extendedDose get() = getUnsignedShort(command.objectData, 12)

    /** Extended bolus actual dose */
    val extendedPerformed get() = getUnsignedShort(command.objectData, 14)

    fun toShortLocalString(rh: ResourceHelper): String {
        val diff = System.currentTimeMillis() - dateTime.millis
        if (diff >= 60 * 60 * 1000) {
            return rh.gs(R.string.overview_pump_last_bolus_h, standardPerformed * 0.025, diff / 60 / 60 / 1000, (diff / 60 / 1000) % 60)
        } else {
            return rh.gs(R.string.overview_pump_last_bolus_min, standardPerformed * 0.025, diff / 60 / 1000)
        }
    }

    override fun validate(): String? {
        if (standardDose > 0 && extendedDose > 0) return "dose both extended + standard"
        if (standardPerformed > 0 && extendedPerformed > 0) return "performed both extended + standard"
        if (!validateDateTime(command.objectData, 2, info)) return "invalid datetime"
        return null
    }
}
