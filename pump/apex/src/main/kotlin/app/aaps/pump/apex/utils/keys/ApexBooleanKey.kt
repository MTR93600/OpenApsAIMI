package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.apex.R

enum class ApexBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
    override val defaultedBySM: Boolean = false,
) : BooleanPreferenceKey {

    LogInsulinChange("apex_log_insulin_change", true, titleResId = R.string.setting_log_insulin_change, defaultedBySM = true),
    LogBatteryChange("apex_log_battery_change", true, titleResId = R.string.setting_log_battery_change, defaultedBySM = true),
    CalculateBatteryPercentage(
        "apex_calc_battery_percentage", true,
        titleResId = R.string.setting_calc_battery_title,
        summaryResId = R.string.setting_calc_battery_summary,
        defaultedBySM = true
    ),
    HideSerial("apex_hide_serial", true, titleResId = R.string.setting_hide_serial, defaultedBySM = true),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
