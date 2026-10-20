package app.aaps.pump.medtrum.comm.enums

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * Pump state as reported by the Medtrum patch.
 *
 * [label] used to be an Android `@StringRes Int`, which no shared code can hold. It is a [TextRef]
 * now, taken from the generated [MedtrumStrings], so the same name resolves on a phone of either
 * kind and the Android text is unchanged - the generator reads the same `strings.xml`.
 *
 * The `state` byte values and [fromByte] are the protocol-critical parts and are unchanged.
 */
enum class MedtrumPumpState(val state: Byte, val label: TextRef) {
    NONE(0, MedtrumStrings.alarm_none),
    IDLE(1, MedtrumStrings.status_idle),
    FILLED(2, MedtrumStrings.status_filled),
    PRIMING(3, MedtrumStrings.status_priming),
    PRIMED(4, MedtrumStrings.status_primed),
    EJECTING(5, MedtrumStrings.status_ejecting),
    EJECTED(6, MedtrumStrings.status_ejected),
    ACTIVE(32, MedtrumStrings.status_active),
    ACTIVE_ALT(33, MedtrumStrings.status_active),
    LOW_BG_SUSPENDED(64, MedtrumStrings.alarm_low_bg_suspended),
    LOW_BG_SUSPENDED2(65, MedtrumStrings.alarm_low_bg_suspended2),
    AUTO_SUSPENDED(66, MedtrumStrings.alarm_auto_suspended),
    HOURLY_MAX_SUSPENDED(67, MedtrumStrings.alarm_hourly_max_suspended),
    DAILY_MAX_SUSPENDED(68, MedtrumStrings.alarm_daily_max_suspended),
    SUSPENDED(69, MedtrumStrings.alarm_suspended),
    PAUSED(70, MedtrumStrings.alarm_paused),
    OCCLUSION(96, MedtrumStrings.alarm_occlusion),
    EXPIRED(97, MedtrumStrings.alarm_expired),
    RESERVOIR_EMPTY(98, MedtrumStrings.alarm_reservoir_empty),
    PATCH_FAULT(99, MedtrumStrings.alarm_patch_fault),
    PATCH_FAULT2(100, MedtrumStrings.alarm_patch_fault),
    BASE_FAULT(101, MedtrumStrings.alarm_base_fault),
    BATTERY_OUT(102, MedtrumStrings.alarm_battery_out),
    NO_CALIBRATION(103, MedtrumStrings.alarm_no_calibration),
    STOPPED(128.toByte(), MedtrumStrings.status_stopped);

    fun isSuspendedByPump(): Boolean {
        return this in LOW_BG_SUSPENDED..SUSPENDED
    }

    companion object {

        fun fromByte(state: Byte) = MedtrumPumpState.entries.find { it.state == state }
            ?: throw IllegalArgumentException("")
    }
}
