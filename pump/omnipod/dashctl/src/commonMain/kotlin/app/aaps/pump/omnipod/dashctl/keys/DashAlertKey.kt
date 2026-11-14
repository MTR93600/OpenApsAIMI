package app.aaps.pump.omnipod.dashctl.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings

/**
 * The on and off switches of the pod alerts: the same keys and defaults as Android
 * `OmnipodBooleanPreferenceKey` (`ExpirationReminder`, `ExpirationAlarm`, `LowReservoirAlert`).
 * Non-preference keys until the iPhone has a settings screen for Dash (phase 5), as [DashBeepKey].
 */
enum class DashAlertBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
) : BooleanNonPreferenceKey {

    ExpirationReminder("AAPS.Omnipod.expiration_reminder_enabled", true),
    ExpirationAlarm("AAPS.Omnipod.expiration_alarm_enabled", true),
    LowReservoirAlert("AAPS.Omnipod.low_reservoir_alert_enabled", true),
    ;
}

/**
 * The values of the pod alerts: the same keys and defaults as Android `OmnipodIntPreferenceKey`
 * (`ExpirationReminderHours` 4, `ExpirationAlarmHours` 8, `LowReservoirAlertUnits` 20). Android limits
 * them in its settings screen (1-24, 1-8, 5-50); [alertSettings] keeps a stored value inside the same
 * limits, so a value from another source cannot program an alert Android would never send.
 */
enum class DashAlertIntKey(
    override val key: String,
    override val defaultValue: Int,
    val min: Int,
    val max: Int,
) : IntNonPreferenceKey {

    ExpirationReminderHours("AAPS.Omnipod.expiration_reminder_hours_before_expiry", 4, min = 1, max = 24),
    ExpirationAlarmHours("AAPS.Omnipod.expiration_alarm_hours_before_shutdown", 8, min = 1, max = 8),
    LowReservoirAlertUnits("AAPS.Omnipod.low_reservoir_alert_units", 20, min = 5, max = 50),
    ;

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
 * `SoundUncertainBolusNotification`) and `DashBooleanPreferenceKey.SoundDeliverySuspendedNotification`.
 */
enum class DashSoundKey(
    override val key: String,
    override val defaultValue: Boolean,
) : BooleanNonPreferenceKey {

    SoundUncertainTbrNotification("AAPS.Omnipod.notification_uncertain_tbr_sound_enabled", true),
    SoundUncertainSmbNotification("AAPS.Omnipod.notification_uncertain_smb_sound_enabled", true),
    SoundUncertainBolusNotification("AAPS.Omnipod.notification_uncertain_bolus_sound_enabled", true),
    SoundDeliverySuspendedNotification("AAPS.Omnipod.notification_delivery_suspended_sound_enabled", true),
    ;
}
