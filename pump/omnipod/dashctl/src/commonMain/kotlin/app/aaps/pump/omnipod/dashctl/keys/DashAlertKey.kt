package app.aaps.pump.omnipod.dashctl.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings

/**
 * The on and off switches of the pod alerts: the same keys and defaults as Android
 * `OmnipodBooleanPreferenceKey` (`ExpirationReminder`, `ExpirationAlarm`, `LowReservoirAlert`), with
 * the titles of the Android settings screen. The iPhone settings screen shows them
 * (`dashSettingsScreen`).
 */
enum class DashAlertBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
) : BooleanPreferenceKey {

    ExpirationReminder(
        "AAPS.Omnipod.expiration_reminder_enabled", true,
        title = DashStrings.dash_pref_expiration_reminder_enabled,
        summary = DashStrings.dash_pref_expiration_reminder_enabled_summary
    ),
    ExpirationAlarm(
        "AAPS.Omnipod.expiration_alarm_enabled", true,
        title = DashStrings.dash_pref_expiration_alarm_enabled,
        summary = DashStrings.dash_pref_expiration_alarm_enabled_summary
    ),
    LowReservoirAlert("AAPS.Omnipod.low_reservoir_alert_enabled", true, title = DashStrings.dash_pref_low_reservoir_alert_enabled),
    ;

    override val preferenceType: PreferenceType = PreferenceType.SWITCH
}

/**
 * The values of the pod alerts: the same keys and defaults as Android `OmnipodIntPreferenceKey`
 * (`ExpirationReminderHours` 4, `ExpirationAlarmHours` 8, `LowReservoirAlertUnits` 20), with the same
 * limits as the Android settings screen (1-24, 1-8, 5-50) and the same switch each one depends on.
 * [alertSettings] also keeps a stored value inside those limits, so a value from another source cannot
 * program an alert Android would never send.
 */
enum class DashAlertIntKey(
    override val key: String,
    override val defaultValue: Int,
    override val min: Int,
    override val max: Int,
    override val title: TextRef,
    override val dependency: BooleanPreferenceKey?,
) : IntPreferenceKey {

    ExpirationReminderHours(
        "AAPS.Omnipod.expiration_reminder_hours_before_expiry", 4, min = 1, max = 24,
        title = DashStrings.dash_pref_expiration_reminder_hours_before_expiry,
        dependency = DashAlertBooleanKey.ExpirationReminder
    ),
    ExpirationAlarmHours(
        "AAPS.Omnipod.expiration_alarm_hours_before_shutdown", 8, min = 1, max = 8,
        title = DashStrings.dash_pref_expiration_alarm_hours_before_shutdown,
        dependency = DashAlertBooleanKey.ExpirationAlarm
    ),
    LowReservoirAlertUnits(
        "AAPS.Omnipod.low_reservoir_alert_units", 20, min = 5, max = 50,
        title = DashStrings.dash_pref_low_reservoir_alert_units,
        dependency = DashAlertBooleanKey.LowReservoirAlert
    ),
    ;

    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD

    companion object {

        /**
         * The alert settings of the user, as Android `updateAlertConfiguration` reads them, from [readBoolean]
         * and [readInt] (on the phone `Preferences.get`).
         */
        fun alertSettings(readBoolean: (BooleanNonPreferenceKey) -> Boolean, readInt: (IntNonPreferenceKey) -> Int): DashAlertSettings {
            fun value(key: DashAlertIntKey) = readInt(key).coerceIn(key.min, key.max)
            return DashAlertSettings(
                expiration = DashExpirationAlertSettings(
                    reminderEnabled = readBoolean(DashAlertBooleanKey.ExpirationReminder),
                    reminderHours = value(ExpirationReminderHours),
                    alarmEnabled = readBoolean(DashAlertBooleanKey.ExpirationAlarm),
                    alarmHours = value(ExpirationAlarmHours),
                ),
                lowReservoir = DashLowReservoirAlertSettings(
                    enabled = readBoolean(DashAlertBooleanKey.LowReservoirAlert),
                    units = value(LowReservoirAlertUnits),
                ),
            )
        }
    }
}

/**
 * Whether a notice of the Dash driver plays a sound: the same keys and defaults as Android
 * `OmnipodBooleanPreferenceKey` (`SoundUncertainTbrNotification`, `SoundUncertainSmbNotification`,
 * `SoundUncertainBolusNotification`) and `DashBooleanPreferenceKey.SoundDeliverySuspendedNotification`,
 * with the titles of the Android settings screen.
 */
enum class DashSoundKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
) : BooleanPreferenceKey {

    SoundUncertainTbrNotification("AAPS.Omnipod.notification_uncertain_tbr_sound_enabled", true, DashStrings.dash_pref_notification_uncertain_tbr_sound_enabled),
    SoundUncertainSmbNotification("AAPS.Omnipod.notification_uncertain_smb_sound_enabled", true, DashStrings.dash_pref_notification_uncertain_smb_sound_enabled),
    SoundUncertainBolusNotification("AAPS.Omnipod.notification_uncertain_bolus_sound_enabled", true, DashStrings.dash_pref_notification_uncertain_bolus_sound_enabled),
    SoundDeliverySuspendedNotification("AAPS.Omnipod.notification_delivery_suspended_sound_enabled", true, DashStrings.dash_pref_notification_delivery_suspended_sound_enabled),
    ;

    override val preferenceType: PreferenceType = PreferenceType.SWITCH
}
