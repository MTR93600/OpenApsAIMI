package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.BASAL_ACTIVE
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.BASAL_BIT
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.CAPTURE_DELIVERY
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.defaultStatus
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isBasalProgram
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isBolus
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isStopOf
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.SuspendDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A new basal program as Android `OmnipodDashPumpPlugin.setNewBasalProfile`: delivery suspended first
 * (`suspendDeliveryIfActive`), then the program, each with its own active command, against the
 * simulated pod. And `isThisProfileSet` as Android.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, 7-8 the first status read. With
 * basal running ([runningPod]) the suspend is message 9 (answer 10) and the program message 11
 * (answer 12).
 */
class IosDashPumpBasalProgramTest {

    /** A pod whose activation is done: the stored state says COMPLETED, as the activation will set it. */
    private val f = DashPumpOnFakePod(store = MemoryDashPodStateStore().apply { saved = DashPodState(activationProgress = ActivationProgress.COMPLETED) })

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    /** Android `observeSuspendDeliveryCommand(hasBasalBeepEnabled = false)`. */
    private fun androidSuspend(sequence: Int): String = SuspendDeliveryCommand.Builder()
        .setSequenceNumber(sequence.toShort())
        .setUniqueId(UNIQUE_ID)
        .setNonce(IosDashPump.NONCE)
        .setBeepType(BeepType.SILENT)
        .build().encoded.toParityHex()

    /** Android `observeSendProgramBasalCommand(basalProgram, hasBasalBeepEnabled = false)` at [timeMs]. */
    private fun androidProgram(sequence: Int, program: BasalProgram, timeMs: Long): String = ProgramBasalCommand.Builder()
        .setUniqueId(UNIQUE_ID)
        .setSequenceNumber(sequence.toShort())
        .setNonce(IosDashPump.NONCE)
        .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
        .setBasalProgram(program)
        .setCurrentTime(timeMs)
        .build().encoded.toParityHex()

    /** Paired, basal running, one status read that shows it. The outcomes so far are cleared. */
    private suspend fun runningPod() {
        f.nowMs = SENT_AT
        f.pairAndConnect()
        f.basalRunning = true
        f.pump.getPumpStatus("first status")
        assertTrue(f.stateManager.podStatus?.deliveryStatus?.basalActive() == true, "fixture: the status shows basal running")
        assertEquals(2, f.stateManager.messageSequenceNumber, "fixture")
        f.decided.clear()
    }

    // ---------------------------------------------------------------- the order of events

    @Test
    fun `a program on a running pod sends a suspend then the program with the Android bytes`() = runTest {
        runningPod()
        val start = f.pod.commands.size

        val result = f.pump.setNewBasalProfile(PROFILE)

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        val sent = f.pod.commands.drop(start)
        assertEquals(listOf(androidSuspend(2), androidProgram(4, PROGRAM, SENT_AT)), sent.map { it.toParityHex() })
        assertEquals(
            listOf(DashCommandKind.SUSPEND_DELIVERY to DashCommandConfirmation.SUCCESS, DashCommandKind.BASAL_PROGRAM to DashCommandConfirmation.SUCCESS),
            f.decided.map { it.command.kind to it.confirmation },
            "two commands and two outcomes in this order"
        )
        assertTrue(f.decided.none { it.decidedLate })
        assertEquals(PROGRAM, f.decided.last().command.basalProgram)
        assertEquals(SENT_AT, f.decided.last().command.createdAtMs)
        assertEquals(PROGRAM, f.stateManager.basalProgram, "the pod runs the new program")
        assertNull(f.stateManager.activeCommand)
        assertTrue(f.basalRunning)
        assertTrue(f.pump.isThisProfileSet(PROFILE))
        assertFalse(f.pump.isThisProfileSet(OTHER_PROFILE))
    }

    @Test
    fun `no suspend is sent when the last status shows delivery suspended`() = runTest {
        f.pairAndConnect()
        f.pump.getPumpStatus("status first")
        assertTrue(f.stateManager.isSuspended, "fixture: the capture status is SUSPENDED")
        val start = f.pod.commands.size

        assertTrue(f.pump.setNewBasalProfile(PROFILE).success)

        assertEquals(listOf(androidProgram(2, PROGRAM, f.nowMs)), f.pod.commands.drop(start).map { it.toParityHex() })
        assertEquals(DashCommandKind.BASAL_PROGRAM, f.decided.single().command.kind)
    }

    /** Android: `deliveryStatus == SUSPENDED` is false when no status is known, so it suspends. */
    @Test
    fun `a suspend is sent first when no status is known as Android does`() = runTest {
        f.pairAndConnect()
        assertNull(f.stateManager.podStatus)

        assertTrue(f.pump.setNewBasalProfile(PROFILE).success)

        assertEquals(listOf(androidSuspend(0), androidProgram(2, PROGRAM, f.nowMs)), f.pod.commands.map { it.toParityHex() })
    }

    // ---------------------------------------------------------------- answers lost or refused

    @Test
    fun `a lost answer to the program leaves it open until a status decides it`() = runTest {
        runningPod()
        f.pod.dropLinkWhileAnswering = 11 to 0

        val result = f.pump.setNewBasalProfile(PROFILE)

        assertFalse(result.success, result.comment)
        assertFalse(result.enacted)
        assertEquals(DashCommandKind.SUSPEND_DELIVERY, f.decided.single().command.kind, "the suspend is reported on its own")
        val open = assertNotNull(f.stateManager.activeCommand, "the program stays open")
        assertEquals(DashCommandKind.BASAL_PROGRAM, open.kind)
        assertEquals(PROGRAM, open.basalProgram)
        assertNull(f.stateManager.basalProgram, "not running until the pod confirms it")
        assertFalse(f.pump.isThisProfileSet(PROFILE), "delivery is suspended")

        // On the next connection every delivery command is refused until a status decides the program.
        f.pod.dropLinkWhileAnswering = null
        f.nowMs = SENT_AT + ONE_HOUR
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        val before = f.writesFromPump().size
        assertFalse(f.pump.setNewBasalProfile(PROFILE).success)
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertEquals(before, f.writesFromPump().size, "nothing may be sent while the program is open")

        f.pump.getPumpStatus("decide the open program")

        val late = f.decided.last()
        assertEquals(DashCommandKind.BASAL_PROGRAM, late.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, late.confirmation)
        assertTrue(late.decidedLate)
        assertEquals(SENT_AT, late.command.createdAtMs, "the program carries the time it was sent")
        assertEquals(SENT_AT + ONE_HOUR, late.decidedAtMs)
        assertEquals(PROGRAM, f.stateManager.basalProgram)
        assertTrue(f.pump.isThisProfileSet(PROFILE))
    }

    @Test
    fun `a lost answer to the suspend leaves it open and the program is not sent`() = runTest {
        runningPod()
        val start = f.pod.commands.size
        f.pod.dropLinkWhileAnswering = 9 to 0

        val result = f.pump.setNewBasalProfile(PROFILE)

        assertFalse(result.success, result.comment)
        assertEquals(listOf(androidSuspend(2)), f.pod.commands.drop(start).map { it.toParityHex() }, "only the suspend")
        assertEquals(DashCommandKind.SUSPEND_DELIVERY, f.stateManager.activeCommand?.kind)
        assertTrue(f.decided.isEmpty())
        assertNull(f.stateManager.basalProgram)
    }

    /** Android `updateActiveCommand`: SENDING_FAILURE raises the send error, so the program is not sent. */
    @Test
    fun `a suspend that never left the phone stops the program`() = runTest {
        runningPod()
        val start = f.pod.commands.size
        f.pod.dropLinkOnPhoneMessage = 9 to Packet.FIRST

        val result = f.pump.setNewBasalProfile(PROFILE)

        assertFalse(result.success)
        assertEquals(start, f.pod.commands.size, "nothing reached the pod")
        assertNull(f.stateManager.activeCommand, "SENDING_FAILURE is final")
        assertEquals(DashCommandKind.SUSPEND_DELIVERY to DashCommandConfirmation.SENDING_FAILURE, f.decided.single().let { it.command.kind to it.confirmation })
        assertTrue(f.basalRunning, "the pod still runs its basal")
    }

    /**
     * Deliberate difference from Android: a refused program is a failure. Android returns success
     * (no command is open), while the pod, suspended just before, delivers no basal at all.
     */
    @Test
    fun `a program refused by the pod is a failure and the profile is not set`() = runTest {
        runningPod()
        val normal = f.pod.answerFor
        // The pod does not take the program: its status shows the suspend as the last command, delivery suspended.
        f.pod.answerFor = { command -> if (isBasalProgram(command)) defaultStatus(f.lastProgrammingSequence, 0, CAPTURE_DELIVERY) else normal(command) }

        val result = f.pump.setNewBasalProfile(PROFILE)

        assertFalse(result.success, result.comment)
        assertFalse(result.enacted)
        assertEquals(
            listOf(DashCommandKind.SUSPEND_DELIVERY to DashCommandConfirmation.SUCCESS, DashCommandKind.BASAL_PROGRAM to DashCommandConfirmation.DENIED),
            f.decided.map { it.command.kind to it.confirmation }
        )
        assertNull(f.stateManager.activeCommand, "DENIED is final")
        assertNull(f.stateManager.basalProgram, "no program is running")
        assertTrue(f.stateManager.isSuspended)
        assertFalse(f.pump.isThisProfileSet(PROFILE), "a suspended pod: not set, as Android")
    }

    /**
     * Android `isSuspended()` is `podStateManager.isSuspended`. A confirmed suspend followed by a
     * refused program leaves the pod with no basal: the pump must say it is suspended, so the loop
     * does not count a basal that is not delivered.
     */
    @Test
    fun `the pump reports suspended when the program is refused after the suspend`() = runTest {
        runningPod()
        assertFalse(f.pump.isSuspended(), "fixture: basal runs")
        val normal = f.pod.answerFor
        f.pod.answerFor = { command -> if (isBasalProgram(command)) defaultStatus(f.lastProgrammingSequence, 0, CAPTURE_DELIVERY) else normal(command) }

        assertFalse(f.pump.setNewBasalProfile(PROFILE).success)

        assertEquals(DashCommandConfirmation.DENIED, f.decided.last().confirmation)
        assertTrue(f.pump.isSuspended())
        assertFalse(f.pump.isThisProfileSet(PROFILE))
    }

    /** The same when the answer to the program is lost: the last status is the answer to the suspend. */
    @Test
    fun `the pump reports suspended when the answer to the program is lost`() = runTest {
        runningPod()
        f.pod.dropLinkWhileAnswering = 11 to 0

        assertFalse(f.pump.setNewBasalProfile(PROFILE).success)

        assertEquals(DashCommandKind.BASAL_PROGRAM, f.stateManager.activeCommand?.kind, "fixture: the program is open")
        assertTrue(f.pump.isSuspended())
        assertFalse(f.pump.isThisProfileSet(PROFILE))
    }

    @Test
    fun `the pump is not suspended after a confirmed program`() = runTest {
        runningPod()

        assertTrue(f.pump.setNewBasalProfile(PROFILE).success)

        assertFalse(f.pump.isSuspended())
    }

    /** Android: a denied suspend emits `CommandConfirmed(success = false)` and the program goes on. */
    @Test
    fun `a suspend refused by the pod still lets the program go as Android does`() = runTest {
        runningPod()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            if (isStopOf(command, BASAL_BIT)) defaultStatus(f.lastProgrammingSequence, 0, BASAL_ACTIVE) else normal(command)
        }
        val start = f.pod.commands.size

        val result = f.pump.setNewBasalProfile(PROFILE)

        assertEquals(2, f.pod.commands.size - start)
        assertEquals(
            listOf(DashCommandConfirmation.DENIED, DashCommandConfirmation.SUCCESS),
            f.decided.map { it.confirmation }
        )
        assertTrue(result.success, result.comment)
    }

    @Test
    fun `an open command blocks the suspend and nothing is sent`() = runTest {
        runningPod()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command -> if (isBolus(command)) parityHex(NAK) else normal(command) }
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertEquals(DashCommandKind.BOLUS, f.stateManager.activeCommand?.kind)
        val before = f.writesFromPump().size

        val result = f.pump.setNewBasalProfile(PROFILE)

        assertFalse(result.success)
        assertEquals(before, f.writesFromPump().size, "no suspend and no program")
        assertEquals(DashCommandKind.BOLUS, f.stateManager.activeCommand?.kind, "the bolus stays open")
    }

    // ---------------------------------------------------------------- isThisProfileSet and the gates

    /** Android: before the activation the profile counts as set, and nothing is sent. */
    @Test
    fun `before the activation is completed nothing is sent and the profile counts as set`() = runTest {
        val notActivated = DashPumpOnFakePod()
        try {
            notActivated.pairAndConnect()

            val result = notActivated.pump.setNewBasalProfile(PROFILE)

            assertTrue(result.success)
            assertFalse(result.enacted)
            assertTrue(notActivated.pod.commands.isEmpty())
            assertTrue(notActivated.pump.isThisProfileSet(PROFILE))
        } finally {
            notActivated.pod.close()
        }
    }

    @Test
    fun `with no confirmed program the profile is not set`() = runTest {
        runningPod()
        assertNull(f.stateManager.basalProgram)

        assertFalse(f.pump.isThisProfileSet(PROFILE))
    }

    /** Android throws there too: an empty profile is never taken as an empty program. */
    @Test
    fun `a profile with no basal values is refused and nothing is sent`() = runTest {
        runningPod()
        val start = f.pod.commands.size

        assertFailsWith<IllegalArgumentException> { f.pump.isThisProfileSet(BasalOnlyProfile()) }
        val result = f.pump.setNewBasalProfile(BasalOnlyProfile())

        assertFalse(result.success, "deliberate difference: a failure, where Android throws")
        assertEquals(start, f.pod.commands.size)
    }

    private companion object {

        /** The pod id the tests pair with, 0x02420001. */
        const val UNIQUE_ID = 37879809

        /** Android `NakResponseTest`: ILLEGAL_PARAM. */
        const val NAK = "0603070009"

        /** A wall clock time to send at: 2026-11-13 10:00 UTC. */
        const val SENT_AT = 1_794_564_000_000L

        const val ONE_HOUR = 3_600_000L

        /** The profile of the Android `ProfileToBasalProgramTest.validProfile`. */
        val PROFILE = BasalOnlyProfile(0 to 0.5, 18000 to 1.0, 50400 to 3.05)

        val OTHER_PROFILE = BasalOnlyProfile(0 to 0.8)

        val PROGRAM: BasalProgram = mapProfileToBasalProgram(PROFILE, PumpType.OMNIPOD_DASH)
    }
}
