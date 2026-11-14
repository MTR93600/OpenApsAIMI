package app.aaps.pump.omnipod.dashctl.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The alert and sound settings use the keys and defaults of Android `OmnipodBooleanPreferenceKey`,
 * `OmnipodIntPreferenceKey` and `DashBooleanPreferenceKey`, written out here by hand from
 * `:pump:omnipod:common` (which this module cannot see), so a change on one side fails here.
 */
class DashAlertKeyTest {

    @Test
    fun `the alert keys and defaults are the Android ones`() {
        assertEquals(
            listOf(
                "AAPS.Omnipod.expiration_reminder_enabled" to true,
                "AAPS.Omnipod.expiration_alarm_enabled" to true,
                "AAPS.Omnipod.low_reservoir_alert_enabled" to true,
            ),
            DashAlertBooleanKey.entries.map { it.key to it.defaultValue }
        )
        assertEquals(
            listOf(
                "AAPS.Omnipod.expiration_reminder_hours_before_expiry" to listOf(4, 1, 24),
                "AAPS.Omnipod.expiration_alarm_hours_before_shutdown" to listOf(8, 1, 8),
                "AAPS.Omnipod.low_reservoir_alert_units" to listOf(20, 5, 50),
            ),
            DashAlertIntKey.entries.map { it.key to listOf(it.defaultValue, it.min, it.max) }
        )
    }

    @Test
    fun `the sound keys and defaults are the Android ones`() {
        assertEquals(
            listOf(
                "AAPS.Omnipod.notification_uncertain_tbr_sound_enabled" to true,
                "AAPS.Omnipod.notification_uncertain_smb_sound_enabled" to true,
                "AAPS.Omnipod.notification_uncertain_bolus_sound_enabled" to true,
                "AAPS.Omnipod.notification_delivery_suspended_sound_enabled" to true,
            ),
            DashSoundKey.entries.map { it.key to it.defaultValue }
        )
    }

    @Test
    fun `with no saved value the alerts are the Android defaults`() {
        val settings = DashAlertIntKey.alertSettings({ it.defaultValue }, { it.defaultValue })

        assertEquals(
            DashAlertSettings(
                DashExpirationAlertSettings(reminderEnabled = true, reminderHours = 4, alarmEnabled = true, alarmHours = 8),
                DashLowReservoirAlertSettings(enabled = true, units = 20)
            ),
            settings
        )
    }

    @Test
    fun `each saved value goes to its own alert and stays inside the Android limits`() {
        val booleans = mapOf<BooleanNonPreferenceKey, Boolean>(
            DashAlertBooleanKey.ExpirationReminder to false,
            DashAlertBooleanKey.ExpirationAlarm to true,
            DashAlertBooleanKey.LowReservoirAlert to false,
        )
        val ints = mapOf<IntNonPreferenceKey, Int>(
            DashAlertIntKey.ExpirationReminderHours to 30,
            DashAlertIntKey.ExpirationAlarmHours to 3,
            DashAlertIntKey.LowReservoirAlertUnits to 2,
        )

        val settings = DashAlertIntKey.alertSettings({ booleans.getValue(it) }, { ints.getValue(it) })

        assertEquals(
            DashAlertSettings(
                DashExpirationAlertSettings(reminderEnabled = false, reminderHours = 24, alarmEnabled = true, alarmHours = 3),
                DashLowReservoirAlertSettings(enabled = false, units = 5)
            ),
            settings
        )
    }
}
