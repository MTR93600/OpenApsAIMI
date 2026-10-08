package app.aaps.pump.medtrum.comm.enums

/**
 * Pump state as reported by the Medtrum patch.
 *
 * Ported from the Android driver (pump/medtrum). The `labelKey` replaces the Android
 * `R.string` resource ID with a portable string key — the UI layer resolves it.
 * The `state` byte values and [fromByte] are the protocol-critical parts and are
 * unchanged.
 */
enum class MedtrumPumpState(val state: Byte, val labelKey: String) {
    NONE(0, "alarm_none"),
    IDLE(1, "status_idle"),
    FILLED(2, "status_filled"),
    PRIMING(3, "status_priming"),
    PRIMED(4, "status_primed"),
    EJECTING(5, "status_ejecting"),
    EJECTED(6, "status_ejected"),
    ACTIVE(32, "status_active"),
    ACTIVE_ALT(33, "status_active"),
    LOW_BG_SUSPENDED(64, "alarm_low_bg_suspended"),
    LOW_BG_SUSPENDED2(65, "alarm_low_bg_suspended2"),
    AUTO_SUSPENDED(66, "alarm_auto_suspended"),
    HOURLY_MAX_SUSPENDED(67, "alarm_hourly_max_suspended"),
    DAILY_MAX_SUSPENDED(68, "alarm_daily_max_suspended"),
    SUSPENDED(69, "alarm_suspended"),
    PAUSED(70, "alarm_paused"),
    OCCLUSION(96, "alarm_occlusion"),
    EXPIRED(97, "alarm_expired"),
    RESERVOIR_EMPTY(98, "alarm_reservoir_empty"),
    PATCH_FAULT(99, "alarm_patch_fault"),
    PATCH_FAULT2(100, "alarm_patch_fault"),
    BASE_FAULT(101, "alarm_base_fault"),
    BATTERY_OUT(102, "alarm_battery_out"),
    NO_CALIBRATION(103, "alarm_no_calibration"),
    STOPPED(128.toByte(), "status_stopped");

    fun isSuspendedByPump(): Boolean {
        return this in LOW_BG_SUSPENDED..SUSPENDED
    }

    companion object {

        fun fromByte(state: Byte) = MedtrumPumpState.entries.find { it.state == state }
            ?: throw IllegalAccessException("")
    }
}
