package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey

enum class ApexBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
) : BooleanPreferenceKey {
    LogInsulinChange("apex_log_insulin_change", true, defaultedBySM = true),
    LogBatteryChange("apex_log_battery_change", true, defaultedBySM = true),
    CalculateBatteryPercentage("apex_calc_battery_percentage", true, defaultedBySM = true),
    HideSerial("apex_hide_serial", true, defaultedBySM = true),
}
