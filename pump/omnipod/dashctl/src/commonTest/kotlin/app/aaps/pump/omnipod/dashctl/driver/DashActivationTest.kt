package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
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
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * The activation (Android `activatePodPart1` and `activatePodPart2`) at the level of pod commands,
 * against [ActivatingPodModel], with the state stored as bytes ([CrashingStore]). Runs on the JVM
 * and on the iOS simulator.
 */
class DashActivationTest {

    private val nowMs = 1_700_000_000_000L
    private val program = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))
    private val lowReservoir = AlertTrigger.ReservoirVolumeTrigger(200)

    private class Rig(val store: CrashingStore, val model: ActivatingPodModel, nowMs: Long) {

        var state = DashPodStateManager(store)
        val pod = FakeActivationPod(state, model) { nowMs }
        val sleeps = mutableListOf<Long>()
        private val now = nowMs

        fun activation() = DashActivation(state, pod, SilentPodLogger, { now }, { sleeps += it })

        /** The app starts again: a new state manager from what is stored, on the same pod. */
        fun restart() {
            store.crashAfterSaves = null
            state = DashPodStateManager(store)
            pod.state = state
        }
    }

    /** A pod paired on this phone (LTK and unique id stored), nothing else done. */
    private fun pairedRig(store: CrashingStore = CrashingStore(), model: ActivatingPodModel = ActivatingPodModel()): Rig {
        val rig = Rig(store, model, nowMs)
        rig.state.updateFromPairing(uniqueId = ActivatingPodModel.UNIQUE_ID, ltk = ByteArray(16) { it.toByte() })
        return rig
    }

    private suspend fun Rig.activateAll(trigger: AlertTrigger.ReservoirVolumeTrigger? = lowReservoir) {
        activation().activatePart1(trigger)
        activation().activatePart2(program, userConfiguredExpirationReminderHours = 4, userConfiguredExpirationAlarmHours = 7)
    }

    @Test
    fun `a full activation sends the Android steps in Android order`() = runTest {
        val rig = pairedRig()
        rig.activateAll()
        val t = CommandType.entries.associateBy { it.value }
        assertEquals(
            listOf(
                CommandType.GET_VERSION, CommandType.SET_UNIQUE_ID,
                CommandType.PROGRAM_ALERTS, CommandType.PROGRAM_ALERTS, // low reservoir, then lump of coal
                CommandType.PROGRAM_INSULIN, CommandType.GET_STATUS, // prime, then its check
                CommandType.PROGRAM_INSULIN, CommandType.PROGRAM_ALERTS, // basal program, expiration alerts
                CommandType.PROGRAM_INSULIN, CommandType.GET_STATUS, // cannula, then its check
            ),
            rig.pod.types().map { t[it] }
        )
        assertEquals(ActivationProgress.COMPLETED, rig.state.activationProgress)
        assertEquals(1, rig.model.primes)
        assertEquals(1, rig.model.cannulaInsertions)
        assertEquals(1, rig.model.basalPrograms)
        assertEquals(program, rig.state.basalProgram)
        assertTrue(rig.state.isActivatedAndRunning)
        assertNull(rig.state.activationStepInFlight)
        // One second per pulse after the prime (52) and after the cannula (10), as Android.
        assertEquals(listOf(52_000L, 10_000L), rig.sleeps)
    }

    @Test
    fun `each step is stored in Android order`() = runTest {
        val rig = pairedRig()
        rig.activateAll()
        // Every progress value was written to the store, one after the other, none skipped.
        assertEquals(ActivationProgress.entries.toList(), rig.store.progressHistory)
    }

    @Test
    fun `no low reservoir alert is sent when it is off`() = runTest {
        val rig = pairedRig()
        rig.activation().activatePart1(null)
        assertEquals(1, rig.pod.types().count { it == CommandType.PROGRAM_ALERTS.value })
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, rig.state.activationProgress)
    }

    @Test
    fun `the prime is built from what the pod told in SetUniqueId`() = runTest {
        val rig = pairedRig()
        rig.activation().activatePart1(lowReservoir)
        val prime = rig.pod.sent.single { FakeActivationPod.isBolus(it) }
        // 52 pulses of 0.05 U = 2.6 U at the prime pulse rate 8: the same builder Android uses.
        val expected = ProgramBolusCommand.Builder()
            .setUniqueId(ActivatingPodModel.UNIQUE_ID.toInt())
            .setSequenceNumber(8)
            .setNonce(DashActivation.NONCE)
            .setNumberOfUnits(2.6)
            .setDelayBetweenPulsesInEighthSeconds(8)
            .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
            .build()
        assertEquals(expected.encoded.toList(), prime.toList())
    }

    @Test
    fun `the expiration alerts have the Android values`() = runTest {
        val rig = pairedRig()
        rig.activateAll()
        val alerts = rig.pod.sent.filter { it[6] == CommandType.PROGRAM_ALERTS.value }.last()
        // The pod reports 2 minutes since activation at a status read now, and a pod life of 80 h:
        // expiry = now - 2 min + 72 h, so 72 h - 2 min are left.
        // Alarm 7 h: 72 h - 2 min - 7 h + 8 h = 4378 min. Imminent: 72 h - 2 min - 1 h + 8 h = 4738 min.
        // Reminder 4 h: 72 h - 2 min - 4 h = 4078 min. Alarm duration: 7 x 60 - 60 = 360 min.
        val expected = ProgramAlertsCommand.Builder()
            .setUniqueId(ActivatingPodModel.UNIQUE_ID.toInt())
            .setSequenceNumber(((alerts[4].toInt() and 0xff) shr 2 and 0x0f).toShort())
            .setNonce(DashActivation.NONCE)
            .setMultiCommandFlag(true)
            .setAlertConfigurations(
                listOf(
                    AlertConfiguration(AlertType.EXPIRATION, true, 360, false, AlertTrigger.TimerTrigger(4378), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX3),
                    AlertConfiguration(AlertType.EXPIRATION_IMMINENT, true, 0, false, AlertTrigger.TimerTrigger(4738), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX4),
                    AlertConfiguration(AlertType.USER_SET_EXPIRATION, true, 0, false, AlertTrigger.TimerTrigger(4078), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN),
                )
            )
            .build()
        assertEquals(expected.encoded.toList(), alerts.toList())
    }

    @Test
    fun `with the alerts off the alarms are disabled and the reminder is left out`() = runTest {
        val rig = pairedRig()
        rig.activation().activatePart1(null)
        rig.activation().activatePart2(program, userConfiguredExpirationReminderHours = null, userConfiguredExpirationAlarmHours = null)
        val alerts = rig.pod.sent.filter { it[6] == CommandType.PROGRAM_ALERTS.value }.last()
        // Android: the default 7 h for the delay, disabled; the reminder at 81 h before the end is
        // negative and is not sent.
        val expected = ProgramAlertsCommand.Builder()
            .setUniqueId(ActivatingPodModel.UNIQUE_ID.toInt())
            .setSequenceNumber(((alerts[4].toInt() and 0xff) shr 2 and 0x0f).toShort())
            .setNonce(DashActivation.NONCE)
            .setMultiCommandFlag(true)
            .setAlertConfigurations(
                listOf(
                    AlertConfiguration(AlertType.EXPIRATION, false, 360, false, AlertTrigger.TimerTrigger(4378), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX3),
                    AlertConfiguration(AlertType.EXPIRATION_IMMINENT, false, 0, false, AlertTrigger.TimerTrigger(4738), BeepType.FOUR_TIMES_BIP_BEEP, BeepRepetitionType.XXX4),
                )
            )
            .build()
        assertEquals(expected.encoded.toList(), alerts.toList())
    }

    @Test
    fun `whole minutes are those of Java Duration toMinutes`() {
        assertEquals(1L, DashActivation.wholeMinutes(119_999L))
        assertEquals(0L, DashActivation.wholeMinutes(-1L)) // -1 ms: -1 s, then -1 / 60 = 0
        assertEquals(-1L, DashActivation.wholeMinutes(-60_001L)) // -61 s
    }

    @Test
    fun `part 1 is refused once phase 1 is done and part 2 before it`() = runTest {
        val rig = pairedRig()
        assertFailsWith<DashActivationException> { rig.activation().activatePart2(program, 4, 7) }
        assertTrue(rig.pod.sent.isEmpty())
        rig.activation().activatePart1(lowReservoir)
        val before = rig.pod.sent.size
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertEquals(before, rig.pod.sent.size)
        rig.activation().activatePart2(program, 4, 7)
        assertFailsWith<DashActivationException> { rig.activation().activatePart2(program, 4, 7) }
    }

    @Test
    fun `a lost answer to the prime does not prime again`() = runTest {
        val rig = pairedRig()
        rig.pod.loseAnswerOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertEquals(ActivationProgress.PRIMING, rig.state.activationStepInFlight)
        assertEquals(1, rig.model.primes)
        val before = rig.pod.sent.size
        rig.activation().activatePart1(lowReservoir)
        // A status first (the pod took it), then the check of the prime: no bolus.
        assertEquals(listOf(CommandType.GET_STATUS.value, CommandType.GET_STATUS.value), rig.pod.types().drop(before))
        assertEquals(1, rig.model.primes)
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, rig.state.activationProgress)
        assertNull(rig.state.activationStepInFlight)
    }

    @Test
    fun `a lost answer to the cannula bolus does not insert it again`() = runTest {
        val rig = pairedRig()
        rig.activation().activatePart1(lowReservoir)
        rig.pod.loseAnswerOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart2(program, 4, 7) }
        assertEquals(ActivationProgress.INSERTING_CANNULA, rig.state.activationStepInFlight)
        rig.activation().activatePart2(program, 4, 7)
        assertEquals(1, rig.model.cannulaInsertions)
        assertEquals(0, rig.model.otherBoluses)
        assertEquals(ActivationProgress.COMPLETED, rig.state.activationProgress)
    }

    @Test
    fun `a prime that never left the phone is sent on the next try`() = runTest {
        val rig = pairedRig()
        rig.pod.failBeforeRadioOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertNull(rig.state.activationStepInFlight)
        assertEquals(0, rig.model.primes)
        rig.activation().activatePart1(lowReservoir)
        assertEquals(1, rig.model.primes)
    }

    @Test
    fun `a prime the pod did not take is sent again after a status`() = runTest {
        val rig = pairedRig()
        rig.pod.loseAnswerOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        // The bytes went but the pod did not take them: it still reports UID_SET.
        rig.model.podStatus = PodStatus.UID_SET
        rig.model.primes = 0
        val before = rig.pod.sent.size
        rig.activation().activatePart1(lowReservoir)
        assertEquals(CommandType.GET_STATUS.value, rig.pod.types()[before])
        assertTrue(FakeActivationPod.isBolus(rig.pod.sent[before + 1]))
        assertEquals(1, rig.model.primes)
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, rig.state.activationProgress)
    }

    @Test
    fun `a step in flight with no status read sends nothing more`() = runTest {
        val rig = pairedRig()
        rig.pod.loseAnswerOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        rig.pod.noStatus = true
        val before = rig.pod.sent.size
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertEquals(before, rig.pod.sent.size)
        assertEquals(ActivationProgress.PRIMING, rig.state.activationStepInFlight)
    }

    @Test
    fun `a step in flight with an unexpected pod status sends nothing more`() = runTest {
        val rig = pairedRig()
        rig.pod.loseAnswerOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        rig.model.podStatus = PodStatus.ALARM
        val before = rig.pod.sent.size
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertEquals(listOf(CommandType.GET_STATUS.value), rig.pod.types().drop(before))
        assertEquals(ActivationProgress.PRIMING, rig.state.activationStepInFlight)
        assertEquals(1, rig.model.primes)
    }

    /**
     * The status read of a step in flight gets a NAK (a lost answer can leave the message sequence
     * out of step). The stored pod status is still the one from before the bolus, which says "not
     * taken": it must not decide. The activation stops, the mark stays, nothing more is sent.
     */
    @Test
    fun `a NAK to the status read of a prime in flight does not prime again`() = runTest {
        val rig = pairedRig()
        rig.pod.loseAnswerOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertEquals(PodStatus.UID_SET, rig.state.reportedPodStatus, "the stored status is from before the prime")
        rig.pod.nakStatus = true
        val before = rig.pod.sent.size
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertEquals(listOf(CommandType.GET_STATUS.value), rig.pod.types().drop(before))
        assertEquals(ActivationProgress.PRIMING, rig.state.activationStepInFlight)
        assertEquals(1, rig.model.primes)
        assertEquals(0, rig.model.otherBoluses)
        // A real status then decides: the pod took it.
        rig.pod.nakStatus = false
        rig.activation().activatePart1(lowReservoir)
        assertEquals(1, rig.model.primes)
        assertEquals(0, rig.model.otherBoluses)
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, rig.state.activationProgress)
    }

    @Test
    fun `a NAK to the status read of a cannula bolus in flight does not insert it again`() = runTest {
        val rig = pairedRig()
        rig.activation().activatePart1(lowReservoir)
        rig.pod.loseAnswerOf = FakeActivationPod::isBolus
        assertFailsWith<DashActivationException> { rig.activation().activatePart2(program, 4, 7) }
        assertEquals(PodStatus.BASAL_PROGRAM_SET, rig.state.reportedPodStatus, "the stored status is from before the cannula bolus")
        rig.pod.nakStatus = true
        val before = rig.pod.sent.size
        assertFailsWith<DashActivationException> { rig.activation().activatePart2(program, 4, 7) }
        assertEquals(listOf(CommandType.GET_STATUS.value), rig.pod.types().drop(before))
        assertEquals(ActivationProgress.INSERTING_CANNULA, rig.state.activationStepInFlight)
        assertEquals(1, rig.model.cannulaInsertions)
        assertEquals(0, rig.model.otherBoluses)
        rig.pod.nakStatus = false
        rig.activation().activatePart2(program, 4, 7)
        assertEquals(1, rig.model.cannulaInsertions)
        assertEquals(0, rig.model.otherBoluses)
        assertEquals(ActivationProgress.COMPLETED, rig.state.activationProgress)
    }

    @Test
    fun `a pod still priming fails the check and the next try goes on without a second prime`() = runTest {
        val rig = pairedRig(model = ActivatingPodModel().apply { engagingAfterPrime = true })
        assertFailsWith<DashActivationException> { rig.activation().activatePart1(lowReservoir) }
        assertEquals(ActivationProgress.PRIMING, rig.state.activationProgress)
        rig.activation().activatePart1(lowReservoir)
        assertEquals(ActivationProgress.PHASE_1_COMPLETED, rig.state.activationProgress)
        assertEquals(1, rig.model.primes)
    }

    @Test
    fun `the running program is the one the pod took`() = runTest {
        val rig = pairedRig()
        rig.activation().activatePart1(lowReservoir)
        rig.pod.loseAnswerOf = { it[6] == CommandType.PROGRAM_ALERTS.value }
        assertFailsWith<DashActivationException> { rig.activation().activatePart2(program, 4, 7) }
        assertEquals(ActivationProgress.PROGRAMMED_BASAL, rig.state.activationProgress)
        val other = BasalProgram(listOf(BasalProgram.Segment(0, 48, 200)))
        rig.activation().activatePart2(other, 4, 7)
        // The pod got `program` only: Android would store `other`.
        assertEquals(1, rig.model.basalPrograms)
        assertEquals(program, rig.state.basalProgram)
    }

    /**
     * The app stops after each write of the state, in turn, from the first activation step to the
     * last. After each stop the app starts again from what was stored, on the same pod, and the
     * activation is called again until it is done. The pod must end up activated with exactly one
     * prime and one cannula bolus.
     */
    @Test
    fun `a crash after each stored write resumes and never primes or inserts the cannula twice`() = runTest {
        val reference = pairedRig()
        val savesBefore = reference.store.saves
        reference.activateAll()
        val writes = reference.store.saves - savesBefore
        assertTrue(writes > 10, "only $writes writes")

        for (crashAt in 1..writes) {
            val store = CrashingStore()
            val rig = pairedRig(store)
            store.crashAfterSaves = store.saves + crashAt
            try {
                rig.activateAll()
                fail("no crash at write $crashAt")
            } catch (e: SimulatedCrash) {
                // The app stopped here.
            }
            rig.restart()
            var tries = 0
            while (rig.state.activationProgress != ActivationProgress.COMPLETED) {
                if (++tries > 4) fail("write $crashAt: stuck at ${rig.state.activationProgress}, in flight ${rig.state.activationStepInFlight}")
                try {
                    if (rig.state.activationProgress.isBefore(ActivationProgress.PHASE_1_COMPLETED)) rig.activation().activatePart1(lowReservoir)
                    rig.activation().activatePart2(program, 4, 7)
                } catch (e: DashActivationException) {
                    // A step failed (an answer the pod did not expect): try again, as the user does.
                }
            }
            assertEquals(1, rig.model.primes, "primes after a crash at write $crashAt")
            assertEquals(1, rig.model.cannulaInsertions, "cannula boluses after a crash at write $crashAt")
            assertEquals(0, rig.model.otherBoluses, "other boluses after a crash at write $crashAt")
            assertTrue(rig.state.isActivatedAndRunning, "write $crashAt")
        }
    }

    @Test
    fun `recoverActivationFromPodStatus has the Android table`() {
        val cases = mapOf(
            PodStatus.FILLED to ActivationProgress.NOT_STARTED,
            PodStatus.UID_SET to ActivationProgress.SET_UNIQUE_ID,
            PodStatus.CLUTCH_DRIVE_ENGAGED to ActivationProgress.PRIME_COMPLETED,
            PodStatus.BASAL_PROGRAM_SET to ActivationProgress.PROGRAMMED_BASAL,
            PodStatus.RUNNING_ABOVE_MIN_VOLUME to ActivationProgress.CANNULA_INSERTED,
            PodStatus.RUNNING_BELOW_MIN_VOLUME to ActivationProgress.CANNULA_INSERTED,
        )
        cases.forEach { (podStatus, progress) ->
            val state = DashPodStateManager(CrashingStore())
            state.overwriteForTest { it.copy(reportedPodStatus = podStatus, activationProgress = ActivationProgress.PRIMING, activationStepInFlight = ActivationProgress.INSERTING_CANNULA) }
            assertNull(state.recoverActivationFromPodStatus())
            assertEquals(progress, state.activationProgress, "$podStatus")
            assertNull(state.activationStepInFlight, "$podStatus")
        }
        listOf(PodStatus.ENGAGING_CLUTCH_DRIVE, PodStatus.PRIMING).forEach { podStatus ->
            val state = DashPodStateManager(CrashingStore())
            state.overwriteForTest { it.copy(reportedPodStatus = podStatus, activationProgress = ActivationProgress.PRIMING, activationStepInFlight = ActivationProgress.INSERTING_CANNULA) }
            assertEquals("Busy", state.recoverActivationFromPodStatus())
            assertEquals(ActivationProgress.PRIMING, state.activationProgress)
            assertEquals(ActivationProgress.INSERTING_CANNULA, state.activationStepInFlight)
        }
        listOf(PodStatus.ALARM, PodStatus.DEACTIVATED, null).forEach { podStatus ->
            val state = DashPodStateManager(CrashingStore())
            state.overwriteForTest { it.copy(reportedPodStatus = podStatus, activationProgress = ActivationProgress.PRIMING) }
            assertNull(state.recoverActivationFromPodStatus())
            assertEquals(ActivationProgress.PRIMING, state.activationProgress, "$podStatus")
        }
    }

    @Test
    fun `a pod runs only once activated and reporting a running status`() {
        val state = DashPodStateManager(CrashingStore())
        state.overwriteForTest { it.copy(reportedPodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME, activationProgress = ActivationProgress.CANNULA_INSERTED) }
        assertTrue(!state.isActivatedAndRunning)
        state.updateActivationProgress(ActivationProgress.COMPLETED)
        assertTrue(state.isActivatedAndRunning)
        state.overwriteForTest { it.copy(reportedPodStatus = PodStatus.ALARM) }
        assertTrue(!state.isActivatedAndRunning)
        state.overwriteForTest { it.copy(reportedPodStatus = PodStatus.RUNNING_BELOW_MIN_VOLUME) }
        assertTrue(state.isActivatedAndRunning)
    }
}
