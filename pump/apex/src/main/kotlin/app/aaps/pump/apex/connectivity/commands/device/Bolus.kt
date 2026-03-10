package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.asShortAsByteArray

/** Set bolus.
 *
 * * [dose] - Bolus dose in 0.025U steps
 * * [bgReminderDelay] - Delay before BG Reminder alarm, in 15 minute steps
 */
class Bolus(
    info: ApexDeviceInfo,
    val dose: Int,
    val bgReminderDelay: Int = 0,
) : BaseValueCommand(info) {
    override val valueId = 0x12
    override val isWrite = true

    override val additionalData: ByteArray
        get() = dose.asShortAsByteArray() + bgReminderDelay.toByte()

    override fun toString(): String = "Bolus($dose)"
}