package app.aaps.pump.medtrum.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.MedtrumSettingsDefaults
import app.aaps.pump.medtrum.R

enum class MedtrumIntKey(
    override val key: String,
    override val defaultValue: Int,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
    override var min: Int = Int.MIN_VALUE,
    override var max: Int = Int.MAX_VALUE,
    override val dependency: BooleanPreferenceKey? = null,
) : IntPreferenceKey {

    MedtrumPumpExpiryWarningHours(
        key = "pump_expiry_warning_hour",
        defaultValue = MedtrumSettingsDefaults.PUMP_EXPIRY_WARNING_HOURS,
        titleResId = R.string.pump_warning_expiry_hour_title,
        summaryResId = R.string.pump_warning_expiry_hour_summary,
        min = 48,
        max = 80,
        dependency = MedtrumBooleanKey.MedtrumPatchExpiration
    ),
    MedtrumHourlyMaxInsulin(
        key = "hourly_max_insulin",
        defaultValue = MedtrumSettingsDefaults.HOURLY_MAX_INSULIN,
        titleResId = R.string.hourly_max_insulin_title,
        summaryResId = R.string.hourly_max_insulin_summary,
        min = 10,
        max = 40
    ),
    MedtrumDailyMaxInsulin(
        key = "daily_max_insulin",
        defaultValue = MedtrumSettingsDefaults.DAILY_MAX_INSULIN,
        titleResId = R.string.daily_max_insulin_title,
        summaryResId = R.string.daily_max_insulin_summary,
        min = 20,
        max = 180
    ),
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
