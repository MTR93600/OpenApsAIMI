package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel.Companion.podDefaultStatus
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.CAPTURE_DELIVERY
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isBasalProgram
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.SuspendDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Suspend (Android `OmnipodDashManagerImpl.suspendDelivery`) and resume (Android
 * `OmnipodDashPumpPlugin.resumeDelivery`) against the simulated pod: Android bytes, the open command
 * rule, a suspend during a running bolus, the suspend alerts flag.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, then each command and its ACK:
 * the first command is message 7, the second message 9.
 */
class IosDashPumpSuspendResumeTest {

    private var fixture = DashPumpOnFakePod()
    private val f get() = fixture

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    /** Android `observeSuspendDeliveryCommand(hasBasalBeepEnabled)`. */
    private fun androidSuspend(sequence: Int, beep: BeepType = BeepType.SILENT): String = SuspendDeliveryCommand.Builder()
        .setSequenceNumber(sequence.toShort())
        .setUniqueId(UNIQUE_ID)
        .setNonce(IosDashPump.NONCE)
        .setBeepType(beep)
        .build().encoded.toParityHex()

    /** Android `observeSendProgramBasalCommand(basalProgram, hasBasalBeepEnabled)` at [timeMs]. */
    private fun androidProgram(sequence: Int, program: BasalProgram, timeMs: Long, beep: Boolean = false): String = ProgramBasalCommand.Builder()
        .setUniqueId(UNIQUE_ID)
        .setSequenceNumber(sequence.toShort())
        .setNonce(IosDashPump.NONCE)
        .setProgramReminder(ProgramReminder(atStart = beep, atEnd = false, atInterval = 0))
        .setBasalProgram(program)
        .setCurrentTime(timeMs)
        .build().encoded.toParityHex()

    /** Paired, activated, one status read that shows delivery suspended (the capture status). */
    private suspend fun suspendedPod() {
        f.pairAndConnect()
        f.pump.getPumpStatus("status first")
        assertTrue(f.stateManager.isSuspended, "fixture: the capture status is SUSPENDED")
        f.decided.clear()
    }

    // ---------------------------------------------------------------- suspend

    @Test
    fun `a suspend sends the Android bytes and sets the suspend alerts flag`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true

        val result = f.pump.suspendDelivery()

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertEquals(listOf(androidSuspend(0)), f.pod.commands.map { it.toParityHex() })
        assertEquals(DashCommandKind.SUSPEND_DELIVERY to DashCommandConfirmation.SUCCESS, f.decided.single().let { it.command.kind to it.confirmation })
        assertNull(f.stateManager.activeCommand)
        assertTrue(f.stateManager.suspendAlertsEnabled, "Android sets suspendAlertsEnabled once the answer is read")
        assertTrue(f.pump.isSuspended())
        assertFalse(f.basalRunning)
    }

    @Test
    fun `a suspend beeps when the basal beep setting is on`() = runTest {
        fixture = DashPumpOnFakePod(beeps = DashBeepSettings(basalBeeps = true))
        f.pairAndConnect()

        assertTrue(f.pump.suspendDelivery().success)

        assertEquals(listOf(androidSuspend(0, BeepType.LONG_SINGLE_BEEP)), f.pod.commands.map { it.toParityHex() })
    }

    @Test
    fun `a lost answer to the suspend leaves it open and blocks every delivery command`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0

        val result = f.pump.suspendDelivery()

        assertFalse(result.success, result.comment)
        assertEquals(DashCommandKind.SUSPEND_DELIVERY, f.stateManager.activeCommand?.kind)
        assertTrue(f.decided.isEmpty())
        assertFalse(f.stateManager.suspendAlertsEnabled, "no answer read: Android does not set it either")

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        val before = f.writesFromPump().size
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertFalse(f.pump.suspendDelivery().success)
        assertFalse(f.pump.resumeDelivery(PROFILE).success)
        assertEquals(before, f.writesFromPump().size, "nothing may be sent while the suspend is open")

        f.pump.getPumpStatus("decide the open suspend")

        val late = f.decided.single()
        assertEquals(DashCommandKind.SUSPEND_DELIVERY to DashCommandConfirmation.SUCCESS, late.command.kind to late.confirmation)
        assertTrue(late.decidedLate)
        assertNull(f.stateManager.activeCommand)
    }

    @Test
    fun `a suspend that never left the phone is final and changes nothing`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        f.pod.dropLinkOnPhoneMessage = 7 to Packet.FIRST

        assertFalse(f.pump.suspendDelivery().success)

        assertTrue(f.pod.commands.isEmpty())
        assertNull(f.stateManager.activeCommand)
        assertEquals(DashCommandConfirmation.SENDING_FAILURE, f.decided.single().confirmation)
        assertFalse(f.stateManager.suspendAlertsEnabled)
        assertTrue(f.basalRunning)
    }

    /**
     * Step 5 rule: a confirmed suspend stops a bolus still running, and the bolus is ended with what the
     * answer left. Android leaves it open, so the next status (0 left) counts all of it.
     */
    @Test
    fun `a suspend during a running bolus ends the bolus with what the answer left`() = runTest {
        f.pairAndConnect()
        f.stateManager.createLastBolus(requestedUnits = 2.0, nowMs = f.nowMs)
        f.bolusRunning = true
        f.bolusPulsesRemaining = 20 // 1.0 U not delivered yet

        assertTrue(f.pump.suspendDelivery().success)

        val outcome = f.decided.single()
        assertEquals(DashCommandKind.SUSPEND_DELIVERY, outcome.command.kind)
        val ended = assertNotNull(outcome.endedBolus, "the receiver gets the bolus to record again")
        assertEquals(1.0, assertNotNull(ended.deliveredUnits()), 1e-9)
        assertTrue(assertNotNull(f.stateManager.lastBolus).deliveryComplete)
        assertEquals(listOf(20), f.bolusStops)
    }

    @Test
    fun `a suspend is held back on a pod that is not activated and running`() = runTest {
        f.pairAndConnect(activated = false)

        val result = f.pump.suspendDelivery()

        assertFalse(result.success)
        assertTrue(f.pod.commands.isEmpty())
        assertNull(f.stateManager.activeCommand)
        assertFalse(f.stateManager.suspendAlertsEnabled)
    }

    // ---------------------------------------------------------------- resume

    @Test
    fun `a resume sends the program with the Android bytes and makes it the running program`() = runTest {
        suspendedPod()
        val start = f.pod.commands.size

        val result = f.pump.resumeDelivery(PROFILE)

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertEquals(listOf(androidProgram(2, PROGRAM, f.nowMs)), f.pod.commands.drop(start).map { it.toParityHex() }, "no suspend first")
        val outcome = f.decided.single()
        assertEquals(DashCommandKind.RESUME_DELIVERY to DashCommandConfirmation.SUCCESS, outcome.command.kind to outcome.confirmation)
        assertEquals(PROGRAM, outcome.command.basalProgram)
        assertEquals(PROGRAM, f.stateManager.basalProgram)
        assertTrue(f.basalRunning)
        assertTrue(f.pump.isThisProfileSet(PROFILE))
    }

    @Test
    fun `a resume beeps when the basal beep setting is on`() = runTest {
        fixture = DashPumpOnFakePod(beeps = DashBeepSettings(basalBeeps = true))
        suspendedPod()
        val start = f.pod.commands.size

        assertTrue(f.pump.resumeDelivery(PROFILE).success)

        assertEquals(listOf(androidProgram(2, PROGRAM, f.nowMs, beep = true)), f.pod.commands.drop(start).map { it.toParityHex() })
    }

    /**
     * Deliberate difference from Android: the resumed program is the running one. Android keeps the old
     * program, so a later profile equal to it would count as set while the pod runs the resumed one.
     */
    @Test
    fun `a resume with another profile replaces the program stored before`() = runTest {
        suspendedPod()
        f.stateManager.overwriteForTest { it.copy(basalProgram = OTHER_PROGRAM) }

        assertTrue(f.pump.resumeDelivery(PROFILE).success)

        assertEquals(PROGRAM, f.stateManager.basalProgram)
        assertFalse(f.pump.isThisProfileSet(OTHER_PROFILE), "the pod no longer runs the old program")
    }

    @Test
    fun `a resume on a pod that is not suspended is refused and nothing is sent`() = runTest {
        f.pairAndConnect()
        f.basalRunning = true
        f.pump.getPumpStatus("basal runs")
        val start = f.pod.commands.size

        val result = f.pump.resumeDelivery(PROFILE)

        assertFalse(result.success)
        assertEquals("Expected suspended delivery", result.comment)
        assertEquals(start, f.pod.commands.size)
        assertNull(f.stateManager.activeCommand)
    }

    /** Android `observeDeliverySuspended`: `deliveryStatus == SUSPENDED` is false with no status. */
    @Test
    fun `a resume with no status known is refused as Android does`() = runTest {
        f.pairAndConnect()

        assertFalse(f.pump.resumeDelivery(PROFILE).success)

        assertTrue(f.pod.commands.isEmpty())
    }

    @Test
    fun `a lost answer to the resume leaves it open and a late success sets the program`() = runTest {
        suspendedPod()
        f.pod.dropLinkWhileAnswering = 9 to 0

        assertFalse(f.pump.resumeDelivery(PROFILE).success)

        val open = assertNotNull(f.stateManager.activeCommand)
        assertEquals(DashCommandKind.RESUME_DELIVERY, open.kind)
        assertEquals(PROGRAM, open.basalProgram, "the stored command carries the program")
        assertNull(f.stateManager.basalProgram)

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        val before = f.writesFromPump().size
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertEquals(before, f.writesFromPump().size, "nothing may be sent while the resume is open")
        f.pump.getPumpStatus("decide the open resume")

        val late = f.decided.single()
        assertEquals(DashCommandKind.RESUME_DELIVERY to DashCommandConfirmation.SUCCESS, late.command.kind to late.confirmation)
        assertTrue(late.decidedLate)
        assertEquals(PROGRAM, f.stateManager.basalProgram)
    }

    @Test
    fun `a resume refused by the pod is a failure and keeps the program before`() = runTest {
        suspendedPod()
        f.stateManager.overwriteForTest { it.copy(basalProgram = OTHER_PROGRAM) }
        val normal = f.pod.answerFor
        f.pod.answerFor = { command -> if (isBasalProgram(command)) podDefaultStatus(0x08, f.lastProgrammingSequence, 0, CAPTURE_DELIVERY) else normal(command) }

        val result = f.pump.resumeDelivery(PROFILE)

        assertFalse(result.success, result.comment)
        assertEquals(DashCommandConfirmation.DENIED, f.decided.single().confirmation)
        assertNull(f.stateManager.activeCommand)
        assertEquals(OTHER_PROGRAM, f.stateManager.basalProgram)
        assertTrue(f.pump.isSuspended())
    }

    @Test
    fun `a profile that cannot be mapped is refused before anything is sent`() = runTest {
        suspendedPod()
        val start = f.pod.commands.size

        assertFalse(f.pump.resumeDelivery(BasalOnlyProfile()).success)

        assertEquals(start, f.pod.commands.size)
    }

    private companion object {

        /** The pod id the tests pair with, 0x02420001. */
        const val UNIQUE_ID = 37879809

        /** The profile of the Android `ProfileToBasalProgramTest.validProfile`. */
        val PROFILE = BasalOnlyProfile(0 to 0.5, 18000 to 1.0, 50400 to 3.05)

        val OTHER_PROFILE = BasalOnlyProfile(0 to 0.8)

        val PROGRAM: BasalProgram = mapProfileToBasalProgram(PROFILE, PumpType.OMNIPOD_DASH)

        val OTHER_PROGRAM: BasalProgram = mapProfileToBasalProgram(OTHER_PROFILE, PumpType.OMNIPOD_DASH)
    }
}
