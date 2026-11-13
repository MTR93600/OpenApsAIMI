package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.driver.DashActivation
import app.aaps.pump.omnipod.dashctl.driver.FakeActivationPod
import app.aaps.pump.omnipod.dashctl.driver.SimulatedCrash
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.GetVersionCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.SetUniqueIdCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * The activation on the real [IosDashPump], `DashPodConnection`, `MessageIO` and `DashBleLink`,
 * against the simulated pod at the level of BLE events, with [ActivatingPodModel] for the pod side.
 * Android order and bytes, pairing on the link of a new connection (Android `pairNewPod`), a stop of
 * the app around the prime and the cannula bolus, and the `observePodRunning` gate.
 */
class IosDashPumpActivationTest {

    private var fixture: DashPumpOnFakePod = DashPumpOnFakePod()
    private val f get() = fixture
    private var model = ActivatingPodModel()

    private val program = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))
    private val lowReservoir = AlertTrigger.ReservoirVolumeTrigger(200)

    /** Android `Ids(podStateManager)` before the pod has a unique id: the controller id plus one. */
    private val podUniqueId = Ids(null).podId.toLong().toInt()

    @AfterTest
    fun closePod() {
        f.pod.close()
    }

    private fun newPod() {
        f.answerAsActivatingPod(model)
        f.nowMs = NOW
    }

    private suspend fun activateAll() {
        val part1 = f.pump.activatePodPart1(lowReservoir)
        assertTrue(part1.success, part1.comment)
        val part2 = f.pump.activatePodPart2(program, userConfiguredExpirationReminderHours = 4, userConfiguredExpirationAlarmHours = 7)
        assertTrue(part2.success, part2.comment)
    }

    /** The app stops and starts again: a new pump from what is stored, on the same pod, connected again. */
    private fun restart() {
        val saved = f.store.saved
        f.pod.reconnect()
        val pod = f.pod
        fixture = DashPumpOnFakePod(store = MemoryDashPodStateStore().apply { this.saved = saved }, pod = pod)
        f.answerAsActivatingPod(model)
        f.nowMs = NOW
    }

    // ---------------------------------------------------------------- Android order and bytes

    @Test
    fun `a new pod is paired on the new link then activated with the Android bytes in Android order`() = runTest {
        assertIs<DashConnectResult.LinkReady>(f.lastConnect)
        newPod()

        activateAll()

        val reminderOff = ProgramReminder(atStart = false, atEnd = false, atInterval = 0)
        val expected = listOf(
            GetVersionCommand.Builder().setSequenceNumber(0).setUniqueId(GetVersionCommand.DEFAULT_UNIQUE_ID).build(),
            SetUniqueIdCommand.Builder().setSequenceNumber(2).setUniqueId(podUniqueId)
                .setLotNumber(135556289).setPodSequenceNumber(611540).setInitializationTime(NOW).build(),
            ProgramAlertsCommand.Builder().setUniqueId(podUniqueId).setSequenceNumber(4).setNonce(NONCE).setMultiCommandFlag(false)
                .setAlertConfigurations(
                    listOf(AlertConfiguration(AlertType.LOW_RESERVOIR, true, 0, false, lowReservoir, BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX))
                ).build(),
            ProgramAlertsCommand.Builder().setUniqueId(podUniqueId).setSequenceNumber(6).setNonce(NONCE).setMultiCommandFlag(false)
                .setAlertConfigurations(
                    listOf(AlertConfiguration(AlertType.EXPIRATION, true, 55, false, AlertTrigger.TimerTrigger(5), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX5))
                ).build(),
            // The prime: 52 pulses (2.6 U) at the prime pulse rate 8, from the SetUniqueId answer.
            ProgramBolusCommand.Builder().setUniqueId(podUniqueId).setSequenceNumber(8).setNonce(NONCE)
                .setNumberOfUnits(2.6).setDelayBetweenPulsesInEighthSeconds(8).setProgramReminder(reminderOff).build(),
            status(10),
            ProgramBasalCommand.Builder().setUniqueId(podUniqueId).setSequenceNumber(12).setNonce(NONCE)
                .setProgramReminder(reminderOff).setBasalProgram(program).setCurrentTime(NOW).build(),
            // 72 h - 2 min left: alarm at 7 h (4378 min), imminent (4738 min), reminder at 4 h (4078 min).
            ProgramAlertsCommand.Builder().setUniqueId(podUniqueId).setSequenceNumber(14).setNonce(NONCE).setMultiCommandFlag(true)
                .setAlertConfigurations(
                    listOf(
                        AlertConfiguration(AlertType.EXPIRATION, true, 360, false, AlertTrigger.TimerTrigger(4378), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX3),
                        AlertConfiguration(AlertType.EXPIRATION_IMMINENT, true, 0, false, AlertTrigger.TimerTrigger(4738), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX4),
                        AlertConfiguration(
                            AlertType.USER_SET_EXPIRATION, true, 0, false, AlertTrigger.TimerTrigger(4078), BeepType.FOUR_TIMES_BIP_BEEP,
                            BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
                        ),
                    )
                ).build(),
            // The cannula: 10 pulses (0.5 U) at the prime pulse rate; the 4-bit sequence went round.
            ProgramBolusCommand.Builder().setUniqueId(podUniqueId).setSequenceNumber(0).setNonce(NONCE)
                .setNumberOfUnits(0.5).setDelayBetweenPulsesInEighthSeconds(8).setProgramReminder(reminderOff).build(),
            status(2),
        )
        assertEquals(expected.map { it.encoded.toParityHex() }, f.pod.commands.map { it.toParityHex() })
        assertEquals(ActivationProgress.COMPLETED, f.stateManager.activationProgress)
        assertEquals(program, f.stateManager.basalProgram)
        assertTrue(f.pump.isPaired())
        assertFalse(f.pump.isBusy())
        assertEquals(1, model.primes)
        assertEquals(1, model.cannulaInsertions)
        f.pod.assertNoProblems()
    }

    @Test
    fun `the pump is busy from the start of part 1 to the end of part 2`() = runTest {
        newPod()
        assertFalse(f.pump.isBusy())
        assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        assertTrue(f.pump.isBusy(), "half activated: the queue must send nothing")
        assertTrue(f.pump.activatePodPart2(program, 4, 7).success)
        assertFalse(f.pump.isBusy())
    }

    @Test
    fun `part 2 before part 1 sends nothing`() = runTest {
        newPod()
        val result = f.pump.activatePodPart2(program, 4, 7)
        assertFalse(result.success)
        assertTrue(f.pod.commands.isEmpty())
        assertFalse(f.pump.isPaired(), "nothing paired either")
    }

    // ---------------------------------------------------------------- a stop of the app

    /**
     * The app stops at the first or the second write of the state while [step] is in flight: the
     * mark before the bytes, or the count of the send after the pod took them. It starts again, the
     * pod connects again, and the activation is called again. The pod gets that bolus once.
     */
    private suspend fun crashWhileInFlight(step: ActivationProgress, write: Int) {
        newPod()
        if (step == ActivationProgress.INSERTING_CANNULA) assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        var seen = 0
        f.store.crashWhen = { it.activationStepInFlight == step && ++seen == write }
        try {
            if (step == ActivationProgress.PRIMING) f.pump.activatePodPart1(lowReservoir) else f.pump.activatePodPart2(program, 4, 7)
            fail("no stop")
        } catch (e: SimulatedCrash) {
            // The app stopped here.
        }
        restart()
        assertIs<DashConnectResult.SessionReady>(f.lastConnect, "a paired pod gets a session at once")
        if (f.stateManager.activationProgress.isBefore(ActivationProgress.PHASE_1_COMPLETED)) {
            assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        }
        val part2 = f.pump.activatePodPart2(program, 4, 7)
        assertTrue(part2.success, part2.comment)
        assertEquals(ActivationProgress.COMPLETED, f.stateManager.activationProgress)
        assertEquals(1, model.primes, "primes")
        assertEquals(1, model.cannulaInsertions, "cannula boluses")
        assertEquals(0, model.otherBoluses)
        f.pod.assertNoProblems()
    }

    @Test
    fun `a stop before the prime bytes go sends the prime once after a status`() = runTest {
        crashWhileInFlight(ActivationProgress.PRIMING, write = 1)
    }

    @Test
    fun `a stop after the pod took the prime does not prime again`() = runTest {
        crashWhileInFlight(ActivationProgress.PRIMING, write = 2)
    }

    @Test
    fun `a stop before the cannula bytes go inserts the cannula once after a status`() = runTest {
        crashWhileInFlight(ActivationProgress.INSERTING_CANNULA, write = 1)
    }

    @Test
    fun `a stop after the pod took the cannula bolus does not insert it again`() = runTest {
        crashWhileInFlight(ActivationProgress.INSERTING_CANNULA, write = 2)
    }

    // ---------------------------------------------------------------- observePodRunning

    @Test
    fun `delivery commands are refused before the activation is completed`() = runTest {
        f.pairAndConnect(activated = false)

        val bolus = f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 })
        val tbr = f.pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL)
        val cancel = f.pump.cancelTempBasal(true)

        assertFalse(bolus.success)
        assertFalse(tbr.success)
        assertFalse(cancel.success)
        assertTrue(f.pod.commands.isEmpty(), "nothing sent")
        assertTrue(f.decided.isEmpty())
        assertTrue(f.stateManager.activeCommand == null)
    }

    @Test
    fun `delivery commands are refused while the activation is half done`() = runTest {
        newPod()
        assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        val before = f.pod.commands.size

        assertFalse(f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 }).success)
        assertFalse(f.pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)

        assertEquals(before, f.pod.commands.size)
        assertEquals(1, model.primes)
        assertEquals(0, model.otherBoluses)
    }

    @Test
    fun `a bolus goes once the activation is completed`() = runTest {
        newPod()
        activateAll()

        val result = f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 0.5 })

        assertTrue(result.success, result.comment)
        assertEquals(1, model.otherBoluses)
    }

    @Test
    fun `a bolus is refused when the pod reports an alarm`() = runTest {
        newPod()
        activateAll()
        model.podStatus = PodStatus.ALARM
        f.pump.getPumpStatus("alarm")
        val before = f.pod.commands.size

        val result = f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 0.5 })

        assertFalse(result.success)
        assertEquals(before, f.pod.commands.size)
        assertEquals(0, model.otherBoluses)
    }

    @Test
    fun `the stop of all delivery is not held back on a pod that does not run`() = runTest {
        f.pairAndConnect(activated = false)

        f.pump.stopDelivery()

        assertEquals(CommandType.STOP_DELIVERY.value, f.pod.commands.single()[6])
    }

    // ---------------------------------------------------------------- getPumpStatus before the activation

    @Test
    fun `before the activation only a status the user asked for is read and it recovers the progress`() = runTest {
        newPod()
        assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        val before = f.pod.commands.size

        f.pump.getPumpStatus("routine")
        assertEquals(before, f.pod.commands.size, "Android: no status read during an activation")

        f.pump.getPumpStatus(IosDashPump.REQUESTED_BY_USER)
        assertEquals(before + 1, f.pod.commands.size)
        // CLUTCH_DRIVE_ENGAGED: Android recoverActivationFromPodStatus gives PRIME_COMPLETED.
        assertEquals(ActivationProgress.PRIME_COMPLETED, f.stateManager.activationProgress)
        // Part 1 then finishes with no command, and part 2 goes on as before.
        assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        assertEquals(before + 1, f.pod.commands.size)
        assertTrue(f.pump.activatePodPart2(program, 4, 7).success)
        assertEquals(1, model.primes)
        assertEquals(1, model.cannulaInsertions)
    }

    /**
     * The cannula bytes went and the pod took them, but the app stopped before the answer. A status
     * the user asks for gets a NAK: the stored pod status is still BASAL_PROGRAM_SET from before the
     * bolus, so `recoverActivationFromPodStatus` must not run on it. It would clear the mark and go
     * back to PROGRAMMED_BASAL, and the next part 2 would insert the cannula a second time.
     */
    @Test
    fun `a NAK to a status the user asks for keeps the cannula bolus in flight`() = runTest {
        newPod()
        assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        var seen = 0
        f.store.crashWhen = { it.activationStepInFlight == ActivationProgress.INSERTING_CANNULA && ++seen == 2 }
        try {
            f.pump.activatePodPart2(program, 4, 7)
            fail("no stop")
        } catch (e: SimulatedCrash) {
            // The app stopped here.
        }
        restart()
        assertEquals(ActivationProgress.INSERTING_CANNULA, f.stateManager.activationStepInFlight)
        assertEquals(PodStatus.BASAL_PROGRAM_SET, f.stateManager.reportedPodStatus)
        f.pod.answerFor = { command ->
            if (command[6] == CommandType.GET_STATUS.value) parityHex(FakeActivationPod.NAK_RESPONSE) else model.answer(command)
        }
        val before = f.pod.commands.size

        f.pump.getPumpStatus(IosDashPump.REQUESTED_BY_USER)

        assertEquals(before + 1, f.pod.commands.size, "the status was asked for")
        assertEquals(ActivationProgress.INSERTING_CANNULA, f.stateManager.activationStepInFlight, "a NAK is not a status: the mark stays")
        assertEquals(ActivationProgress.UPDATED_EXPIRATION_ALERTS, f.stateManager.activationProgress)
        // A real status then decides, in part 2: the pod took the cannula bolus.
        f.answerAsActivatingPod(model)
        val part2 = f.pump.activatePodPart2(program, 4, 7)
        assertTrue(part2.success, part2.comment)
        assertEquals(1, model.cannulaInsertions)
        assertEquals(0, model.otherBoluses)
    }

    /**
     * Android `getStatus` starts with `observeUniqueIdSet`: before SET_UNIQUE_ID nothing is sent, also
     * when the user asks, so `recoverActivationFromPodStatus` is not reached either.
     */
    @Test
    fun `a status the user asks for is not read before the pod has its unique id`() = runTest {
        newPod()
        f.store.crashWhen = { it.activationProgress == ActivationProgress.GOT_POD_VERSION }
        try {
            f.pump.activatePodPart1(lowReservoir)
            fail("no stop")
        } catch (e: SimulatedCrash) {
            // The app stopped after GetVersion, with the pod paired.
        }
        restart()
        val before = f.pod.commands.size

        f.pump.getPumpStatus(IosDashPump.REQUESTED_BY_USER)

        assertEquals(before, f.pod.commands.size, "Android observeUniqueIdSet: nothing sent")
        assertEquals(ActivationProgress.GOT_POD_VERSION, f.stateManager.activationProgress)
        assertTrue(f.pump.activatePodPart1(lowReservoir).success)
        assertEquals(1, model.primes)
    }

    // ---------------------------------------------------------------- alert settings (step 7)

    /**
     * The Android wizard stores the low reservoir settings after part 1 and the expiration settings
     * after part 2 (`updateLowReservoirAlertSettings`, `updateExpirationAlertSettings`), so the alert
     * configuration with the same settings sends nothing afterwards.
     */
    @Test
    fun `the alert settings are stored after each part and the same settings send nothing later`() = runTest {
        newPod()
        val lowSettings = DashLowReservoirAlertSettings(enabled = true, units = 20)
        val expirationSettings = DashExpirationAlertSettings(reminderEnabled = true, reminderHours = 4, alarmEnabled = true, alarmHours = 7)

        assertTrue(f.pump.activatePodPart1(lowReservoir, lowSettings).success)
        assertEquals(lowSettings, f.stateManager.lowReservoirAlertSettings)
        assertEquals(null, f.stateManager.expirationAlertSettings)
        assertTrue(f.pump.activatePodPart2(program, 4, 7, expirationSettings).success)
        assertEquals(expirationSettings, f.stateManager.expirationAlertSettings)
        val before = f.pod.commands.size

        val update = f.pump.updateAlertConfiguration(DashAlertSettings(expirationSettings, lowSettings))

        assertTrue(update.success)
        assertFalse(update.enacted)
        assertEquals(before, f.pod.commands.size)
    }

    @Test
    fun `the low reservoir settings are not stored when part 1 fails`() = runTest {
        newPod()
        f.pod.dropLinkWhileAnswering = 7 to 0 // the answer to GetVersion is lost: part 1 stops

        assertFalse(f.pump.activatePodPart1(lowReservoir, DashLowReservoirAlertSettings(enabled = true, units = 20)).success)

        assertEquals(null, f.stateManager.lowReservoirAlertSettings)
    }

    private fun status(sequence: Int) = GetStatusCommand.Builder()
        .setUniqueId(podUniqueId)
        .setSequenceNumber(sequence.toShort())
        .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
        .build()

    private companion object {

        const val NOW = 1_700_000_000_000L
        const val NONCE = DashActivation.NONCE
    }
}
