package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel.Companion.podDefaultStatus
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.ALARM
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBeepsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.SilenceAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Silence alerts, program alerts, the suspend alerts and the alert settings, and the test beep, as the
 * Android Dash plugin sends them, against the simulated pod. Each one goes through the open command
 * rule as Android (`executeProgrammingCommand`).
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, then each command and its ACK.
 */
class IosDashPumpAlertsTest {

    private val f = DashPumpOnFakePod()

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    /** Paired, activated, one status read (message 7) at [nowMs]. */
    private suspend fun runningPodWithStatus() {
        f.pairAndConnect()
        f.pump.getPumpStatus("status first")
        f.decided.clear()
    }

    private fun alertsCommand(sequence: Int, alerts: List<AlertConfiguration>): String = ProgramAlertsCommand.Builder()
        .setUniqueId(UNIQUE_ID)
        .setSequenceNumber(sequence.toShort())
        .setNonce(IosDashPump.NONCE)
        .setAlertConfigurations(alerts)
        .setMultiCommandFlag(false)
        .build().encoded.toParityHex()

    // ---------------------------------------------------------------- silence alerts

    @Test
    fun `silence alerts sends the alerts of the last status with the Android bytes`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        runningPodWithStatus()
        assertEquals(setOf(AlertType.LOW_RESERVOIR), f.stateManager.activeAlerts, "fixture")
        val start = f.pod.commands.size

        val result = f.pump.silenceAlerts()

        assertTrue(result.success, result.comment)
        val expected = SilenceAlertsCommand.Builder()
            .setUniqueId(UNIQUE_ID)
            .setSequenceNumber(2)
            .setNonce(IosDashPump.NONCE)
            .setAlertTypes(setOf(AlertType.LOW_RESERVOIR))
            .build()
        assertEquals(listOf(expected.encoded.toParityHex()), f.pod.commands.drop(start).map { it.toParityHex() })
        assertEquals(DashCommandKind.SILENCE_ALERTS to DashCommandConfirmation.SUCCESS, f.decided.single().let { it.command.kind to it.confirmation })
        assertEquals(emptySet(), f.stateManager.activeAlerts, "the answer shows the alert silenced")
    }

    @Test
    fun `silence alerts with no status known is refused and nothing is sent`() = runTest {
        f.pairAndConnect()

        val result = f.pump.silenceAlerts()

        assertFalse(result.success)
        assertEquals("No active alerts", result.comment)
        assertTrue(f.pod.commands.isEmpty())
    }

    @Test
    fun `a lost answer to silence alerts leaves it open and blocks every delivery command`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        runningPodWithStatus()
        f.pod.dropLinkWhileAnswering = 9 to 0

        assertFalse(f.pump.silenceAlerts().success)

        assertEquals(DashCommandKind.SILENCE_ALERTS, f.stateManager.activeCommand?.kind)
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        val before = f.writesFromPump().size
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertFalse(f.pump.playTestBeep().success)
        assertEquals(before, f.writesFromPump().size)
        f.pump.getPumpStatus("decide")
        assertEquals(DashCommandConfirmation.SUCCESS, f.decided.single().confirmation)
        assertTrue(f.decided.single().decidedLate)
    }

    @Test
    fun `alerts are held back on a pod in alarm as Android observePodRunning`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        f.podStatusCode = ALARM
        runningPodWithStatus()
        val start = f.pod.commands.size

        assertFalse(f.pump.silenceAlerts().success)
        assertFalse(f.pump.disableSuspendAlerts().success)
        assertFalse(f.pump.playTestBeep().success)

        assertEquals(start, f.pod.commands.size)
        assertNull(f.stateManager.activeCommand)
    }

    // ---------------------------------------------------------------- suspend alerts

    @Test
    fun `disable suspend alerts sends the Android alert and clears the flag`() = runTest {
        runningPodWithStatus()
        f.stateManager.updateSuspendAlertsEnabled(true)
        val start = f.pod.commands.size

        val result = f.pump.disableSuspendAlerts()

        assertTrue(result.success, result.comment)
        // Android `disableSuspendAlerts`.
        val android = listOf(
            AlertConfiguration(
                AlertType.SUSPEND_ENDED, enabled = false, durationInMinutes = 0, autoOff = false, AlertTrigger.TimerTrigger(0),
                BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
            )
        )
        assertEquals(listOf(alertsCommand(2, android)), f.pod.commands.drop(start).map { it.toParityHex() })
        assertEquals(DashCommandKind.PROGRAM_ALERTS, f.decided.single().command.kind)
        assertFalse(f.stateManager.suspendAlertsEnabled)
    }

    /**
     * As Android: `disableSuspendAlerts` clears the flag whenever its command did not fail to send,
     * because `executeProgrammingCommand` also completes after a refused command. The result stays a
     * failure (fail closed).
     */
    @Test
    fun `disable suspend alerts refused by the pod clears the flag as Android`() = runTest {
        runningPodWithStatus()
        f.stateManager.updateSuspendAlertsEnabled(true)
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            if (command[6] == CommandType.PROGRAM_ALERTS.value) podDefaultStatus(f.podStatusCode, f.lastProgrammingSequence) else normal(command)
        }

        assertFalse(f.pump.disableSuspendAlerts().success)

        assertEquals(DashCommandConfirmation.DENIED, f.decided.single().confirmation)
        assertFalse(f.stateManager.suspendAlertsEnabled)
    }

    /** As Android: a not confirmed command also completes `executeProgrammingCommand`, so the flag is cleared. */
    @Test
    fun `a lost answer to disable suspend alerts clears the flag as Android`() = runTest {
        runningPodWithStatus()
        f.stateManager.updateSuspendAlertsEnabled(true)
        f.pod.dropLinkWhileAnswering = 9 to 0

        assertFalse(f.pump.disableSuspendAlerts().success)

        assertEquals(DashCommandKind.PROGRAM_ALERTS, f.stateManager.activeCommand?.kind)
        assertTrue(f.decided.isEmpty())
        assertFalse(f.stateManager.suspendAlertsEnabled)
    }

    /** As Android: a send failure makes `executeProgrammingCommand` fail, and the flag stays. */
    @Test
    fun `disable suspend alerts that never left the phone keeps the flag`() = runTest {
        runningPodWithStatus()
        f.stateManager.updateSuspendAlertsEnabled(true)
        f.pod.dropLinkOnPhoneMessage = 9 to Packet.FIRST

        assertFalse(f.pump.disableSuspendAlerts().success)

        assertEquals(DashCommandConfirmation.SENDING_FAILURE, f.decided.single().confirmation)
        assertTrue(f.stateManager.suspendAlertsEnabled)
    }

    // ---------------------------------------------------------------- alert configuration

    @Test
    fun `the alert configuration sends the four Android alerts and stores the settings`() = runTest {
        runningPodWithStatus()
        val start = f.pod.commands.size

        val result = f.pump.updateAlertConfiguration(SETTINGS)

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        // Android `updateAlertConfiguration` with 72 h - 2 min of pod life left: reminder 9 h before
        // (3778 min), alarm 7 h before + 8 h grace (4378 min), imminent 1 h before + 8 h (4738 min).
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
        assertEquals(listOf(alertsCommand(2, android)), f.pod.commands.drop(start).map { it.toParityHex() })
        assertEquals(SETTINGS.expiration, f.stateManager.expirationAlertSettings)
        assertEquals(SETTINGS.lowReservoir, f.stateManager.lowReservoirAlertSettings)
        assertTrue(f.stateManager.sameAlertSettings(SETTINGS))
    }

    @Test
    fun `the same alert settings again send nothing`() = runTest {
        runningPodWithStatus()
        assertTrue(f.pump.updateAlertConfiguration(SETTINGS).success)
        val start = f.pod.commands.size

        val again = f.pump.updateAlertConfiguration(SETTINGS)

        assertTrue(again.success)
        assertFalse(again.enacted)
        assertEquals(start, f.pod.commands.size)
    }

    @Test
    fun `the alert configuration sends nothing when no pod runs`() = runTest {
        f.pairAndConnect(activated = false)

        val result = f.pump.updateAlertConfiguration(SETTINGS)

        assertTrue(result.success)
        assertFalse(result.enacted)
        assertTrue(f.pod.commands.isEmpty())
    }

    /**
     * Deliberate difference from Android: the settings are stored only when the pod confirmed the
     * alerts. Android stores them also after a lost answer, and then never sends them again.
     */
    @Test
    fun `alert settings the pod did not confirm are not stored and are sent again`() = runTest {
        runningPodWithStatus()
        f.pod.dropLinkWhileAnswering = 9 to 0

        assertFalse(f.pump.updateAlertConfiguration(SETTINGS).success)

        assertNull(f.stateManager.expirationAlertSettings)
        assertNull(f.stateManager.lowReservoirAlertSettings)
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.pump.getPumpStatus("decide the open alerts")
        assertEquals(DashCommandConfirmation.SUCCESS, f.decided.last().confirmation)
        val start = f.pod.commands.size

        assertTrue(f.pump.updateAlertConfiguration(SETTINGS).success)

        assertEquals(1, f.pod.commands.size - start, "sent again")
        assertTrue(f.stateManager.sameAlertSettings(SETTINGS))
    }

    // ---------------------------------------------------------------- test beep

    @Test
    fun `the test beep sends the Android program beeps command`() = runTest {
        runningPodWithStatus()
        val start = f.pod.commands.size

        val result = f.pump.playTestBeep()

        assertTrue(result.success, result.comment)
        val off = ProgramReminder(atStart = false, atEnd = false, atInterval = 0)
        val android = ProgramBeepsCommand.Builder()
            .setUniqueId(UNIQUE_ID)
            .setSequenceNumber(2)
            .setBasalReminder(off)
            .setTempBasalReminder(off)
            .setBolusReminder(off)
            .setImmediateBeepType(BeepType.LONG_SINGLE_BEEP)
            .build()
        assertEquals(listOf(android.encoded.toParityHex()), f.pod.commands.drop(start).map { it.toParityHex() })
        assertEquals(DashCommandKind.PROGRAM_BEEPS, f.decided.single().command.kind)
    }

    private companion object {

        /** The pod id the tests pair with, 0x02420001. */
        const val UNIQUE_ID = 37879809

        /** `AlertType.LOW_RESERVOIR` in the alert byte of a status (1 shl 4). */
        const val LOW_RESERVOIR_BIT = 0x10

        val SETTINGS = DashAlertSettings(
            expiration = DashExpirationAlertSettings(reminderEnabled = true, reminderHours = 9, alarmEnabled = true, alarmHours = 7),
            lowReservoir = DashLowReservoirAlertSettings(enabled = true, units = 20),
        )
    }
}
