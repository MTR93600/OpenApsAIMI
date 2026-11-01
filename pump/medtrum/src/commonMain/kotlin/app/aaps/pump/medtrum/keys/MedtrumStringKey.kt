package app.aaps.pump.medtrum.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.StringValidator
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.MedtrumSettingsDefaults
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * The text pump settings. Shared code, so the Android and the iPhone settings screen show the
 * same keys with the same defaults.
 */
enum class MedtrumStringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, TextRef> = emptyMap(),
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
    override val validator: StringValidator = StringValidator.NONE
) : StringPreferenceKey {

    /**
     * The alarm the patch gives. The stored value is the `AlarmSetting` code as text. Every code is
     * listed here; the settings screen shows only the codes the pump model offers
     * (`MedtrumInsulinLimits.alarmEntries`).
     */
    MedtrumAlarmSettings(
        key = "alarm_setting",
        // "6": beep only.
        defaultValue = MedtrumSettingsDefaults.ALARM_SETTING.code.toString(),
        title = MedtrumStrings.alarm_setting_title,
        summary = MedtrumStrings.alarm_setting_summary,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "0" to MedtrumStrings.alarm_setting_light_vibrate_beep,
            "1" to MedtrumStrings.alarm_setting_light_vibrate,
            "2" to MedtrumStrings.alarm_setting_light_beep,
            "3" to MedtrumStrings.alarm_setting_light,
            "4" to MedtrumStrings.alarm_setting_vibrate_beep,
            "5" to MedtrumStrings.alarm_setting_vibrate,
            "6" to MedtrumStrings.alarm_setting_beep,
            "7" to MedtrumStrings.alarm_setting_silent
        )
    ),
    ;
}
