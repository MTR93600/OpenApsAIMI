package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The alert configurations of Android `OmnipodDashPumpPlugin.updateAlertConfiguration` and
 * `disableSuspendAlerts`, value by value. The expected lists are the Android code with the numbers
 * worked out by hand.
 */
class DashAlertsTest {

    private fun hex(alerts: List<AlertConfiguration>): List<String> = alerts.map { it.encoded.toParityHex() }

    private fun settings(
        reminderEnabled: Boolean = true,
        reminderHours: Int = 9,
        alarmEnabled: Boolean = true,
        alarmHours: Int = 7,
        lowEnabled: Boolean = true,
        lowUnits: Int = 20,
    ) = DashAlertSettings(
        DashExpirationAlertSettings(reminderEnabled, reminderHours, alarmEnabled, alarmHours),
        DashLowReservoirAlertSettings(lowEnabled, lowUnits),
    )

    @Test
    fun `the four alerts have the Android values in the Android order`() {
        // 72 h - 2 min of pod life left.
        val alerts = DashAlerts.updateAlertConfigurations(settings(), 72 * HOUR - 2 * MINUTE, SilentPodLogger)

        val android = listOf(
            AlertConfiguration(
                AlertType.LOW_RESERVOIR, true, 0, false, AlertTrigger.ReservoirVolumeTrigger(200),
                BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX
            ),
            AlertConfiguration(
                AlertType.USER_SET_EXPIRATION, true, 0, false, AlertTrigger.TimerTrigger(3778),
                BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
            ),
            AlertConfiguration(AlertType.EXPIRATION, true, 360, false, AlertTrigger.TimerTrigger(4378), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX3),
            AlertConfiguration(AlertType.EXPIRATION_IMMINENT, true, 0, false, AlertTrigger.TimerTrigger(4738), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX3),
        )
        assertEquals(hex(android), hex(alerts))
    }

    @Test
    fun `alerts turned off are still sent with their delays as Android`() {
        val alerts = DashAlerts.updateAlertConfigurations(
            settings(reminderEnabled = false, reminderHours = 2, alarmEnabled = false, alarmHours = 1, lowEnabled = false, lowUnits = 10),
            40 * HOUR,
            SilentPodLogger
        )

        val android = listOf(
            AlertConfiguration(
                AlertType.LOW_RESERVOIR, false, 0, false, AlertTrigger.ReservoirVolumeTrigger(100),
                BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX
            ),
            AlertConfiguration(
                AlertType.USER_SET_EXPIRATION, false, 0, false, AlertTrigger.TimerTrigger((38 * 60).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
            ),
            // (1 - 1) h of alarm duration; 40 - 1 + 8 = 47 h.
            AlertConfiguration(AlertType.EXPIRATION, false, 0, false, AlertTrigger.TimerTrigger((47 * 60).toShort()), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX3),
            AlertConfiguration(
                AlertType.EXPIRATION_IMMINENT, false, 0, false, AlertTrigger.TimerTrigger((47 * 60).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX3
            ),
        )
        assertEquals(hex(android), hex(alerts))
    }

    /**
     * Ported as Android (reported): a negative delay is only logged, and the negative minutes are sent.
     * Java `Duration.toMinutes` goes toward zero: -90.5 s is -1 min.
     */
    @Test
    fun `a negative delay is sent as Android sends it`() {
        // 2 h - 90.5 s left, reminder 3 h before: -1 h - 90.5 s.
        val alerts = DashAlerts.updateAlertConfigurations(settings(reminderHours = 3), 2 * HOUR - 90_500L, SilentPodLogger)

        val reminder = AlertConfiguration(
            AlertType.USER_SET_EXPIRATION, true, 0, false, AlertTrigger.TimerTrigger((-61).toShort()),
            BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
        )
        assertEquals(reminder.encoded.toParityHex(), alerts[1].encoded.toParityHex())
    }

    @Test
    fun `the suspend ended alert is turned off with the Android values`() {
        val android = listOf(
            AlertConfiguration(
                AlertType.SUSPEND_ENDED, enabled = false, durationInMinutes = 0, autoOff = false, AlertTrigger.TimerTrigger(0),
                BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
            )
        )
        assertEquals(hex(android), hex(DashAlerts.disableSuspendAlerts()))
    }

    @Test
    fun `a stop beeps only when its beep setting is on`() {
        assertEquals(BeepType.SILENT, DashBeepSettings.stopBeep(false))
        assertEquals(BeepType.LONG_SINGLE_BEEP, DashBeepSettings.stopBeep(true))
    }

    private companion object {

        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
    }
}
