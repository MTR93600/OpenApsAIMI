package app.aaps.pump.medtrum.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.MedtrumSettingsDefaults
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * The number pump settings. Shared code, so the Android and the iPhone settings screen show the
 * same keys with the same defaults and limits.
 *
 * The upper limit of the two insulin maximums depends on the pump model. `MedtrumInsulinLimits`
 * sets it before a settings screen is shown.
 */
enum class MedtrumIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override var min: Int = Int.MIN_VALUE,
    override var max: Int = Int.MAX_VALUE,
    override val dependency: BooleanPreferenceKey? = null,
) : IntPreferenceKey {

    MedtrumPumpExpiryWarningHours(
        key = "pump_expiry_warning_hour",
        defaultValue = MedtrumSettingsDefaults.PUMP_EXPIRY_WARNING_HOURS,
        title = MedtrumStrings.pump_warning_expiry_hour_title,
        summary = MedtrumStrings.pump_warning_expiry_hour_summary,
        min = 48,
        max = 80,
        dependency = MedtrumBooleanKey.MedtrumPatchExpiration
    ),
    MedtrumHourlyMaxInsulin(
        key = "hourly_max_insulin",
        defaultValue = MedtrumSettingsDefaults.HOURLY_MAX_INSULIN,
        title = MedtrumStrings.hourly_max_insulin_title,
        summary = MedtrumStrings.hourly_max_insulin_summary,
        min = 10,
        max = 40
    ),
    MedtrumDailyMaxInsulin(
        key = "daily_max_insulin",
        defaultValue = MedtrumSettingsDefaults.DAILY_MAX_INSULIN,
        title = MedtrumStrings.daily_max_insulin_title,
        summary = MedtrumStrings.daily_max_insulin_summary,
        min = 20,
        max = 180
    ),
    ;
}
