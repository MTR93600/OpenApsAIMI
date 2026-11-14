package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel.Companion.podDefaultStatus
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.ALARM
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isBolus
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.DeactivateCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashPodState
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
 * Deactivation (Android `OmnipodDashPumpPlugin.deactivatePod`) and discard against the simulated pod:
 * the Android bytes, the pod state cleared as Android `podStateManager.reset()`, the open command rule,
 * a running bolus, a pod in alarm.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, then each command and its ACK:
 * the first command is message 7.
 */
class IosDashPumpDeactivationTest {

    private val f = DashPumpOnFakePod()

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    /** Android `observeSendDeactivateCommand`. */
    private fun androidDeactivate(sequence: Int): String = DeactivateCommand.Builder()
        .setSequenceNumber(sequence.toShort())
        .setUniqueId(UNIQUE_ID)
        .setNonce(IosDashPump.NONCE)
        .build().encoded.toParityHex()

    /** Puts things in the state that a reset must clear. */
    private fun fillState() {
        f.stateManager.overwriteForTest {
            it.copy(
                basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100))),
                suspendAlertsEnabled = true,
                expirationAlertSettings = DashExpirationAlertSettings(reminderEnabled = true, reminderHours = 9, alarmEnabled = true, alarmHours = 7),
            )
        }
    }

    @Test
    fun `a deactivation sends the Android bytes and clears the whole pod state`() = runTest {
        f.pairAndConnect()
        fillState()

        val result = f.pump.deactivatePod()

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertEquals(listOf(androidDeactivate(0)), f.pod.commands.map { it.toParityHex() })
        val outcome = f.decided.single()
        assertEquals(DashCommandKind.DEACTIVATE to DashCommandConfirmation.SUCCESS, outcome.command.kind to outcome.confirmation)
        assertEquals(DashPodState(), f.stateManager.currentState(), "nothing is kept, as Android PodState()")
        assertEquals(DashPodState(), f.store.saved, "the cleared state is stored: the key is gone")
        assertFalse(f.pump.isPaired())
        assertFalse(f.pump.hasSession(), "the link of the old pod is closed (isConnected is true: no pod runs, as Android)")
    }

    @Test
    fun `nothing can be sent to the old pod after the deactivation`() = runTest {
        f.pairAndConnect()
        assertTrue(f.pump.deactivatePod().success)
        val before = f.writesFromPump().size

        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertFalse(f.pump.deactivatePod().success)
        f.pump.getPumpStatus(IosDashPump.REQUESTED_BY_USER)

        assertEquals(before, f.writesFromPump().size)
    }

    /** Android `deactivatePod` has no `observePodRunning`: a pod in alarm must still be deactivated. */
    @Test
    fun `a pod in alarm is deactivated`() = runTest {
        f.pairAndConnect()
        f.podStatusCode = ALARM
        f.pump.getPumpStatus("status")
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success, "fixture: delivery is held back on a pod in alarm")
        val start = f.pod.commands.size

        assertTrue(f.pump.deactivatePod().success)

        assertEquals(listOf(androidDeactivate(2)), f.pod.commands.drop(start).map { it.toParityHex() })
        assertEquals(DashPodState(), f.stateManager.currentState())
    }

    @Test
    fun `a pod whose activation stopped half way is deactivated`() = runTest {
        f.pairAndConnect(activated = false)

        assertTrue(f.pump.deactivatePod().success)

        assertEquals(listOf(androidDeactivate(0)), f.pod.commands.map { it.toParityHex() })
        assertEquals(DashPodState(), f.stateManager.currentState())
    }

    @Test
    fun `a deactivation during a running bolus ends the bolus in the outcome`() = runTest {
        f.pairAndConnect()
        f.stateManager.createLastBolus(requestedUnits = 2.0, nowMs = f.nowMs)
        f.bolusRunning = true
        f.bolusPulsesRemaining = 30 // 1.5 U not delivered yet

        assertTrue(f.pump.deactivatePod().success)

        val ended = assertNotNull(f.decided.single().endedBolus, "the receiver records the bolus again with what went in")
        assertEquals(0.5, assertNotNull(ended.deliveredUnits()), 1e-9)
        assertNull(f.stateManager.lastBolus, "then the state is cleared")
    }

    @Test
    fun `a lost answer leaves the deactivation open and the pod state kept`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0

        val result = f.pump.deactivatePod()

        assertFalse(result.success, result.comment)
        assertEquals(DashCommandKind.DEACTIVATE, f.stateManager.activeCommand?.kind)
        assertNotNull(f.stateManager.ltk, "not confirmed: the state stays, as Android")

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        val before = f.writesFromPump().size
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertFalse(f.pump.deactivatePod().success)
        assertEquals(before, f.writesFromPump().size, "nothing may be sent while the deactivation is open")

        f.pump.getPumpStatus("decide the open deactivation")

        val late = f.decided.single()
        assertEquals(DashCommandKind.DEACTIVATE to DashCommandConfirmation.SUCCESS, late.command.kind to late.confirmation)
        assertTrue(late.decidedLate)
        assertNotNull(f.stateManager.ltk, "a late success does not clear the state, as Android")
        assertEquals(PodStatus.DEACTIVATED, f.stateManager.reportedPodStatus)

        // Deactivating again clears it: the pod answers with its status, now DEACTIVATED.
        assertTrue(f.pump.deactivatePod().success)
        assertEquals(DashPodState(), f.stateManager.currentState())
    }

    @Test
    fun `a deactivation that never left the phone keeps the pod state`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkOnPhoneMessage = 7 to Packet.FIRST

        assertFalse(f.pump.deactivatePod().success)

        assertTrue(f.pod.commands.isEmpty())
        assertNull(f.stateManager.activeCommand)
        assertEquals(DashCommandConfirmation.SENDING_FAILURE, f.decided.single().confirmation)
        assertNotNull(f.stateManager.ltk)
    }

    /**
     * Deliberate difference from Android (two pods delivering): a refusal from a pod that still runs
     * keeps the state. Android clears it, and the user would then put a new pod next to a running one.
     */
    @Test
    fun `a deactivation refused by a running pod keeps the pod state`() = runTest {
        f.pairAndConnect()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            if (command[6] == CommandType.DEACTIVATE.value) podDefaultStatus(ActivatingPodModel.RUNNING, f.lastProgrammingSequence) else normal(command)
        }

        val result = f.pump.deactivatePod()

        assertFalse(result.success, result.comment)
        assertEquals(DashCommandConfirmation.DENIED, f.decided.single().confirmation)
        assertNotNull(f.stateManager.ltk)
        assertNull(f.stateManager.activeCommand, "DENIED is final")
    }

    /** As Android: a refusal clears the state when the pod delivers nothing (in alarm). */
    @Test
    fun `a deactivation refused by a pod in alarm clears the pod state as Android`() = runTest {
        f.pairAndConnect()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            if (command[6] == CommandType.DEACTIVATE.value) podDefaultStatus(ALARM, f.lastProgrammingSequence) else normal(command)
        }

        val result = f.pump.deactivatePod()

        assertTrue(result.success, result.comment)
        assertEquals(DashCommandConfirmation.DENIED, f.decided.single().confirmation)
        assertEquals(DashPodState(), f.stateManager.currentState())
    }

    @Test
    fun `an open command blocks the deactivation until a status decides it`() = runTest {
        f.pairAndConnect()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command -> if (isBolus(command)) parityHex(NAK) else normal(command) }
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertEquals(DashCommandKind.BOLUS, f.stateManager.activeCommand?.kind)
        val before = f.writesFromPump().size

        assertFalse(f.pump.deactivatePod().success)

        assertEquals(before, f.writesFromPump().size)
        assertNotNull(f.stateManager.ltk)
    }

    @Test
    fun `discarding the pod clears the state and sends nothing`() = runTest {
        f.pairAndConnect()
        fillState()

        f.pump.discardPod()

        assertTrue(f.pod.commands.isEmpty())
        assertEquals(DashPodState(), f.stateManager.currentState())
        assertEquals(DashPodState(), f.store.saved)
        assertFalse(f.pump.hasSession(), "the link is closed")
        assertTrue(f.pump.isConnected(), "no pod runs, so the queue sees a connected pump, as Android")
        assertTrue(f.decided.isEmpty())
    }

    /**
     * Android records a bolus as soon as it was sent, so its "Discard pod" keeps a bolus the pod may
     * have given. Here a not confirmed bolus is counted only when an outcome comes: discarding the pod
     * must give one, or the insulin is lost from insulin on board and the loop can dose again on top.
     */
    @Test
    fun `discarding the pod gives an open bolus to the receiver as outcome unknown`() = runTest {
        f.pairAndConnect()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command -> if (isBolus(command)) parityHex(NAK) else normal(command) }
        assertFalse(f.pump.deliverTreatment(bolus(2.0)).success)
        assertEquals(DashCommandKind.BOLUS, f.stateManager.activeCommand?.kind)
        f.decided.clear()
        val before = f.writesFromPump().size

        f.pump.discardPod()

        val outcome = f.decided.single()
        assertEquals(DashCommandKind.BOLUS, outcome.command.kind)
        assertEquals(2.0, assertNotNull(outcome.command.requestedBolusUnits), 1e-9)
        assertEquals(DashCommandConfirmation.NOT_CONFIRMED, outcome.confirmation)
        assertTrue(outcome.podDiscarded, "the receiver counts it at the asked amount, as Android")
        assertEquals(DashPodState(), f.stateManager.currentState())
        assertEquals(before, f.writesFromPump().size, "nothing is sent")
    }

    private companion object {

        /** The pod id the tests pair with, 0x02420001. */
        const val UNIQUE_ID = 37879809

        /** Android `NakResponseTest`: ILLEGAL_PARAM. */
        const val NAK = "0603070009"
    }
}
