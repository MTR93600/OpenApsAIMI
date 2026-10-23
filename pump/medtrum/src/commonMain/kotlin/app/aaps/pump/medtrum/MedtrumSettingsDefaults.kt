package app.aaps.pump.medtrum

import app.aaps.pump.medtrum.comm.enums.AlarmSetting

/**
 * The default pump settings, one list for Android and iPhone.
 *
 * The Android preference keys (`MedtrumBooleanKey`, `MedtrumIntKey`, `MedtrumStringKey`) take
 * their default values from here, and so does the shared `MedtrumSessionState` that the iPhone
 * driver sends to the patch. Before this list the iPhone sent its own values at activation: no
 * patch expiry, light + vibrate + beep alarms (an alarm the Nano does not offer), 40 U per hour
 * and 180 U per day. Android sends the values below.
 */
object MedtrumSettingsDefaults {

    /** Android `MedtrumBooleanKey.MedtrumPatchExpiration`: the patch stops after 3 days. */
    const val PATCH_EXPIRATION: Boolean = true

    /** Android `MedtrumBooleanKey.MedtrumWarningNotification`: pump warnings are shown. */
    const val PUMP_WARNING_NOTIFICATION: Boolean = true

    /** Android `MedtrumIntKey.MedtrumPumpExpiryWarningHours`: warn this many hours after the patch start. */
    const val PUMP_EXPIRY_WARNING_HOURS: Int = 72

    /** Android `MedtrumIntKey.MedtrumHourlyMaxInsulin`, in units. */
    const val HOURLY_MAX_INSULIN: Int = 25

    /** Android `MedtrumIntKey.MedtrumDailyMaxInsulin`, in units. */
    const val DAILY_MAX_INSULIN: Int = 80

    /**
     * Android `MedtrumStringKey.MedtrumAlarmSettings` ("6"): beep only. For the Nano and the 300U
     * Android offers only beep and silent.
     */
    val ALARM_SETTING: AlarmSetting = AlarmSetting.BEEP_ONLY
}
