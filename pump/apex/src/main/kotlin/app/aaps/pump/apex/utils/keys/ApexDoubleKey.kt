package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

enum class ApexDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true
): DoublePreferenceKey {
    // 0 == uninitialized
    MaxBasal("apex_max_basal", 0.0, 0.0, 25.0),
    MaxBolus("apex_max_bolus", 0.0, 0.0, 25.0),
    BatteryLowVoltage("apex_low_batt_vtg", 1.2, 1.0, 1.8),
    BatteryHighVoltage("apex_high_batt_vtg", 1.5, 1.0, 1.8),
}