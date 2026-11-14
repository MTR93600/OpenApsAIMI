package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The iPhone Dash settings screen holds what Android `OmnipodDashPumpPlugin.getPreferenceScreenContent`
 * holds: the same keys, in the same order, with the same defaults and limits (Android values are
 * written out here, as the Android keys are not on this classpath).
 */
class DashSettingsTest {

    private val screen = dashSettingsScreen(TextRef.Literal("Omnipod Dash"), icon = null)

    private fun group(key: String): List<PreferenceKey> =
        screen.items.filterIsInstance<PreferenceSubScreenDef>().single { it.key == key }.items.map { assertIs<PreferenceKey>(it) }

    @Test
    fun `the screen has the three groups of Android in the same order`() {
        assertEquals("omnipod_dash_settings", screen.key)
        assertEquals(
            listOf("omnipod_dash_beeps", "omnipod_dash_alerts", "omnipod_dash_notifications"),
            screen.items.map { assertIs<PreferenceSubScreenDef>(it).key }
        )
    }

    @Test
    fun `the beeps are the Android keys and defaults`() {
        val beeps = group("omnipod_dash_beeps").map { assertIs<BooleanPreferenceKey>(it) }
        assertEquals(
            listOf(
                "AAPS.Omnipod.bolus_beeps_enabled" to true,
                "AAPS.Omnipod.basal_beeps_enabled" to false,
                "AAPS.Omnipod.smb_beeps_enabled" to true,
                "AAPS.Omnipod.tbr_beeps_enabled" to false
            ),
            beeps.map { it.key to it.defaultValue }
        )
    }

    @Test
    fun `the alerts are the Android keys with their defaults limits and switches`() {
        val alerts = group("omnipod_dash_alerts")
        assertEquals(
            listOf(
                "AAPS.Omnipod.expiration_reminder_enabled",
                "AAPS.Omnipod.expiration_reminder_hours_before_expiry",
                "AAPS.Omnipod.expiration_alarm_enabled",
                "AAPS.Omnipod.expiration_alarm_hours_before_shutdown",
                "AAPS.Omnipod.low_reservoir_alert_enabled",
                "AAPS.Omnipod.low_reservoir_alert_units"
            ),
            alerts.map { it.key }
        )
        alerts.filterIsInstance<BooleanPreferenceKey>().forEach { assertEquals(true, it.defaultValue, it.key) }
        val numbers = alerts.filterIsInstance<IntPreferenceKey>()
        assertEquals(listOf(Triple(4, 1, 24), Triple(8, 1, 8), Triple(20, 5, 50)), numbers.map { Triple(it.defaultValue, it.min, it.max) })
        assertEquals<List<PreferenceKey?>>(listOf(alerts[0], alerts[2], alerts[4]), numbers.map { it.dependency }, "each number shows under its switch, as Android")
    }

    @Test
    fun `the notification sounds are the Android keys and are on by default`() {
        val sounds = group("omnipod_dash_notifications").map { assertIs<BooleanPreferenceKey>(it) }
        assertEquals(
            listOf(
                "AAPS.Omnipod.notification_uncertain_tbr_sound_enabled",
                "AAPS.Omnipod.notification_uncertain_smb_sound_enabled",
                "AAPS.Omnipod.notification_uncertain_bolus_sound_enabled",
                "AAPS.Omnipod.notification_delivery_suspended_sound_enabled"
            ),
            sounds.map { it.key }
        )
        sounds.forEach { assertEquals(true, it.defaultValue, it.key) }
    }
}
