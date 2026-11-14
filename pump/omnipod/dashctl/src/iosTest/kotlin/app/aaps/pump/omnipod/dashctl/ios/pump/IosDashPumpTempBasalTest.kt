package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.driver.DashActivation
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.TEMP_BASAL_ACTIVE
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.TEMP_BASAL_BIT
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.defaultStatus
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isStopOf
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod.Companion.isTempBasal
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
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
 * A temp basal over a running one, as Android `OmnipodDashPumpPlugin.setTempBasalAbsolute`: the
 * running one is cancelled first (`observeNoActiveTempBasal`), each command with its own active
 * command, against the simulated pod.
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, 7-8 the first status read,
 * 9-10 the first temp basal. With a temp basal running ([runningTempBasal]) the next temp basal
 * sends the cancel as message 11 (ACK 12) and the new temp basal as message 13 (ACK 14).
 */
class IosDashPumpTempBasalTest {

    private val f = DashPumpOnFakePod()

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private suspend fun tbr(rate: Double, minutes: Int) =
        f.pump.setTempBasalAbsolute(rate, minutes, false, PumpSync.TemporaryBasalType.NORMAL)

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    private fun sequenceOf(command: ByteArray) = (command[4].toInt() and 0xff shr 2) and 0x0f

    /** Android `observeSendStopDeliveryCommand(TEMP_BASAL, beeps off)`: the pod id of the tests, the fixed nonce. */
    private fun androidCancel(sequence: Int): String = StopDeliveryCommand.Builder()
        .setUniqueId(UNIQUE_ID)
        .setSequenceNumber(sequence.toShort())
        .setNonce(IosDashPump.NONCE)
        .setDeliveryType(StopDeliveryCommand.DeliveryType.TEMP_BASAL)
        .setBeepType(BeepType.SILENT)
        .build().encoded.toParityHex()

    /** Android `observeSendProgramTempBasalCommand(rate, minutes, tempBasalBeeps = false)`. */
    private fun androidTempBasal(sequence: Int, rate: Double, minutes: Int): String = ProgramTempBasalCommand.Builder()
        .setSequenceNumber(sequence.toShort())
        .setUniqueId(UNIQUE_ID)
        .setNonce(IosDashPump.NONCE)
        .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
        .setRateInUnitsPerHour(rate)
        .setDurationInMinutes(minutes.toShort())
        .build().encoded.toParityHex()

    /**
     * Paired, one status read (no temp basal), then a temp basal of 1 U/h for 30 min that the pod
     * took. The pod now reports a running temp basal. The outcomes so far are cleared.
     */
    private suspend fun runningTempBasal() {
        f.pairAndConnect()
        f.pump.getPumpStatus("first status")
        assertTrue(tbr(1.0, 30).success, "fixture: the first temp basal")
        assertTrue(f.tempBasalRunning, "fixture: the pod runs a temp basal")
        assertTrue(f.stateManager.podStatus?.deliveryStatus?.tempBasalActive() == true, "fixture: the status shows it")
        assertEquals(4, f.stateManager.messageSequenceNumber, "fixture")
        f.decided.clear()
    }

    // ---------------------------------------------------------------- the order of events

    @Test
    fun `a temp basal over a running one sends a cancel then the new one`() = runTest {
        runningTempBasal()
        val start = f.pod.commands.size

        val result = tbr(2.0, 60)

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertEquals(2.0, result.absolute)
        assertEquals(60, result.duration)
        val sent = f.pod.commands.drop(start)
        assertEquals(2, sent.size, "the cancel and the new temp basal, nothing else")
        assertEquals(androidCancel(4), sent[0].toParityHex(), "the cancel: Android bytes")
        assertEquals(androidTempBasal(6, 2.0, 60), sent[1].toParityHex(), "the new temp basal: Android bytes")
        assertEquals(
            listOf(DashCommandKind.STOP_TEMP_BASAL to DashCommandConfirmation.SUCCESS, DashCommandKind.TEMP_BASAL to DashCommandConfirmation.SUCCESS),
            f.decided.map { it.command.kind to it.confirmation },
            "two commands and two outcomes in this order"
        )
        assertTrue(f.decided.none { it.decidedLate })
        assertEquals(2.0, f.decided.last().command.tempBasalRate)
        assertEquals(60, f.decided.last().command.tempBasalMinutes)
        assertNull(f.stateManager.activeCommand)
        assertEquals(8, f.stateManager.messageSequenceNumber)
        assertTrue(f.tempBasalRunning)
    }

    @Test
    fun `no cancel is sent when the last status shows no temp basal`() = runTest {
        f.pairAndConnect()
        f.pump.getPumpStatus("status first")
        val start = f.pod.commands.size

        assertTrue(tbr(1.5, 30).success)

        val sent = f.pod.commands.drop(start)
        assertEquals(listOf(androidTempBasal(2, 1.5, 30)), sent.map { it.toParityHex() })
        assertEquals(DashCommandKind.TEMP_BASAL, f.decided.single().command.kind)
    }

    /** The status read the pump does first, with the GetStatus bytes of Android `getPodStatus`. */
    private fun androidStatus(sequence: Int): String = DashActivation.statusCommand(UNIQUE_ID, sequence.toShort()).encoded.toParityHex()

    /**
     * Deliberate difference from Android (`deliveryStatus?.tempBasalActive() == false` is false with
     * no status, so Android sends the cancel blind): with no status known, a status is read first, and
     * the cancel is decided on it. The pod here runs a temp basal the app does not know about.
     */
    @Test
    fun `with no status known a status is read first and the cancel follows it`() = runTest {
        f.pairAndConnect(basalOfThisSession = false)
        assertNull(f.stateManager.podStatus)
        f.basalRunning = true
        f.tempBasalRunning = true

        assertTrue(tbr(1.5, 30).success)

        assertEquals(listOf(androidStatus(0), androidCancel(2), androidTempBasal(4, 1.5, 30)), f.pod.commands.map { it.toParityHex() })
    }

    @Test
    fun `with no status known and no temp basal on the pod no cancel is sent`() = runTest {
        f.pairAndConnect(basalOfThisSession = false)
        f.basalRunning = true

        assertTrue(tbr(1.5, 30).success)

        assertEquals(listOf(androidStatus(0), androidTempBasal(2, 1.5, 30)), f.pod.commands.map { it.toParityHex() })
    }

    /**
     * Deliberate difference from Android: a temp basal is never sent to a suspended pod (it faults
     * with 0x31 and stops all insulin, or refuses it and the temp basal is counted wrong). The
     * stored status says the basal runs, but it is from before this session; the fresh status shows
     * the pod suspended, so nothing is sent. OmnipodKit `OmniPumpManager.enactTempBasal` refuses a
     * suspended pod (`podSuspended`).
     */
    @Test
    fun `a temp basal on a pod that a fresh status shows suspended is refused and nothing is sent`() = runTest {
        f.pairAndConnect()
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.basalRunning = false
        val start = f.pod.commands.size

        val result = tbr(1.5, 30)

        assertFalse(result.success)
        assertTrue(result.comment.contains("suspended"), result.comment)
        assertEquals(listOf(CommandType.GET_STATUS.value), f.pod.commands.drop(start).map { it[6] }, "only the status read")
        assertTrue(f.stateManager.isSuspended)
        assertTrue(f.decided.isEmpty())
    }

    /** The stored status shows the pod suspended: a status is read first, and when it still shows it, nothing goes. */
    @Test
    fun `a temp basal on a pod the last status shows suspended reads a status and is refused`() = runTest {
        f.pairAndConnect()
        f.giveStatusOfThisSession(basal = false)
        assertTrue(f.stateManager.isSuspended)

        val result = tbr(1.5, 30)

        assertFalse(result.success)
        assertEquals(listOf(CommandType.GET_STATUS.value), f.pod.commands.map { it[6] })
    }

    /** No status of this session can be read (the link is lost on the status): refused, nothing else sent. */
    @Test
    fun `a temp basal is refused when no status of this session can be read`() = runTest {
        f.pairAndConnect()
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        // After the reconnect: 1-2 session, 3 the status read.
        f.pod.dropLinkOnPhoneMessage = 3 to Packet.FIRST
        val start = f.pod.commands.size

        val result = tbr(1.5, 30)

        assertFalse(result.success)
        assertTrue(result.comment.contains("no pod status"), result.comment)
        assertTrue(f.pod.commands.drop(start).isEmpty(), "no temp basal and no cancel")
        assertTrue(f.decided.isEmpty())
    }

    /** The status of this session shows a running pod: no extra status read before the temp basal. */
    @Test
    fun `a temp basal on a status of this session reads no extra status`() = runTest {
        f.pairAndConnect()

        assertTrue(tbr(1.5, 30).success)

        assertEquals(listOf(androidTempBasal(0, 1.5, 30)), f.pod.commands.map { it.toParityHex() })
    }

    // ---------------------------------------------------------------- the cancel is not confirmed

    @Test
    fun `a lost answer to the cancel leaves it open and the new temp basal is not sent`() = runTest {
        runningTempBasal()
        val start = f.pod.commands.size
        f.pod.dropLinkWhileAnswering = 11 to 0

        val result = tbr(2.0, 60)

        assertFalse(result.success, result.comment)
        assertFalse(result.enacted)
        assertEquals(listOf(androidCancel(4)), f.pod.commands.drop(start).map { it.toParityHex() }, "only the cancel")
        val open = assertNotNull(f.stateManager.activeCommand, "the cancel stays open")
        assertEquals(DashCommandKind.STOP_TEMP_BASAL, open.kind)
        assertTrue(open.sent)
        assertTrue(f.decided.isEmpty(), "nothing decided")

        // On the next connection every delivery command is refused until a status decides the cancel.
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        val before = f.writesFromPump().size
        assertFalse(tbr(2.0, 60).success)
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertFalse(f.pump.cancelTempBasal(false).success)
        assertEquals(before, f.writesFromPump().size, "nothing may be sent while the cancel is open")

        f.pump.getPumpStatus("decide the open cancel")

        val outcome = f.decided.single()
        assertEquals(DashCommandKind.STOP_TEMP_BASAL, outcome.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, outcome.confirmation, "the pod took the cancel")
        assertTrue(outcome.decidedLate)
        assertNull(f.stateManager.activeCommand)
        // The status shows no temp basal now: the next one goes without a cancel.
        val next = f.pod.commands.size
        assertTrue(tbr(2.0, 60).success)
        assertEquals(1, f.pod.commands.size - next)
        assertTrue(isTempBasal(f.pod.commands.last()))
    }

    @Test
    fun `a NAK to the cancel keeps it open and blocks the next command on the same link`() = runTest {
        runningTempBasal()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command -> if (isStopOf(command, TEMP_BASAL_BIT)) parityHex(NAK) else normal(command) }
        val start = f.pod.commands.size

        assertFalse(tbr(2.0, 60).success)

        assertEquals(1, f.pod.commands.size - start, "only the cancel")
        assertEquals(DashCommandKind.STOP_TEMP_BASAL, f.stateManager.activeCommand?.kind)
        assertTrue(f.pump.isConnected(), "a NAK does not close the link")
        val before = f.writesFromPump().size
        assertFalse(tbr(2.0, 60).success)
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertEquals(before, f.writesFromPump().size, "the open cancel blocks everything")

        f.answerWithStatus()
        f.pump.getPumpStatus("decide the open cancel")

        assertEquals(DashCommandConfirmation.DENIED, f.decided.single().confirmation, "the pod did not take the cancel")
        assertNull(f.stateManager.activeCommand)
    }

    /** Android `updateActiveCommand`: SENDING_FAILURE raises the send error, so the new temp basal is not sent. */
    @Test
    fun `a cancel that never left the phone stops the new temp basal`() = runTest {
        runningTempBasal()
        val start = f.pod.commands.size
        f.pod.dropLinkOnPhoneMessage = 11 to Packet.FIRST

        val result = tbr(2.0, 60)

        assertFalse(result.success)
        assertTrue(f.pod.commands.size == start, "nothing reached the pod")
        assertNull(f.stateManager.activeCommand, "SENDING_FAILURE is final")
        assertEquals(DashCommandConfirmation.SENDING_FAILURE, f.decided.single().confirmation)
        assertEquals(DashCommandKind.STOP_TEMP_BASAL, f.decided.single().command.kind)
        assertEquals(4, f.stateManager.messageSequenceNumber, "nothing was sent")
        assertTrue(f.tempBasalRunning, "the old temp basal still runs")
    }

    /**
     * Deliberate difference from Android (insulin counted wrong), as OmnipodKit (Trio) `enactTempBasal`:
     * a cancel the pod refused leaves the first temp basal running, so the new one is not sent. Android
     * goes on (`CommandConfirmed(success = false)`), and AAPS would then count a temp basal that does not
     * run. Fails before the fix: the new temp basal went out and the call reported success.
     */
    @Test
    fun `a cancel refused by the pod stops the new temp basal as OmnipodKit does`() = runTest {
        runningTempBasal()
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            // The pod does not take the cancel: its status still shows the first temp basal.
            if (isStopOf(command, TEMP_BASAL_BIT)) defaultStatus(f.lastProgrammingSequence, 0, TEMP_BASAL_ACTIVE) else normal(command)
        }
        val start = f.pod.commands.size

        val result = tbr(2.0, 60)

        assertEquals(1, f.pod.commands.size - start, "the cancel only")
        assertEquals(listOf(DashCommandKind.STOP_TEMP_BASAL to DashCommandConfirmation.DENIED), f.decided.map { it.command.kind to it.confirmation })
        assertFalse(result.success, result.comment)
        assertFalse(result.enacted)
        assertNull(f.stateManager.activeCommand, "DENIED is final: nothing stays open")
    }

    /**
     * With a pod that takes nothing, the new temp basal would get another sequence while the status
     * shows a temp basal running (the first one), and the delivery status check would take it as the
     * new one. Not sending it after the refused cancel keeps that from happening.
     */
    @Test
    fun `a refused cancel sends no new temp basal that the running one could be taken for`() = runTest {
        runningTempBasal()
        val firstSequence = f.lastProgrammingSequence
        f.pod.answerFor = { defaultStatus(firstSequence, 0, TEMP_BASAL_ACTIVE) }
        val start = f.pod.commands.size

        val result = tbr(2.0, 60)

        assertFalse(result.success, result.comment)
        assertEquals(1, f.pod.commands.size - start, "the cancel only")
        assertEquals(listOf(DashCommandKind.STOP_TEMP_BASAL to DashCommandConfirmation.DENIED), f.decided.map { it.command.kind to it.confirmation })
        assertTrue(f.stateManager.podStatus?.deliveryStatus?.tempBasalActive() == true, "the first temp basal still runs")
        assertEquals(1.0, f.stateManager.tempBasal?.rate, "the stored temp basal is still the first one")
    }

    // ---------------------------------------------------------------- the time of a command

    /**
     * Android records the stop of a temp basal at the `createdAt` of the cancel (`handleCommandConfirmation`),
     * the time it was made. A cancel decided an hour later must still carry the time it was sent, or a
     * stopped zero temp basal is counted as running for that hour.
     */
    @Test
    fun `a cancel decided late carries the time it was sent`() = runTest {
        runningTempBasal()
        f.nowMs = SENT_AT
        f.pod.dropLinkWhileAnswering = 11 to 0

        assertFalse(tbr(2.0, 60).success)
        assertEquals(SENT_AT, f.stateManager.activeCommand?.createdAtMs, "the open cancel keeps its time")

        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.pump.getPumpStatus("decide the open cancel")

        val outcome = f.decided.single()
        assertEquals(DashCommandKind.STOP_TEMP_BASAL, outcome.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, outcome.confirmation)
        assertTrue(outcome.decidedLate)
        assertEquals(SENT_AT, outcome.command.createdAtMs, "the stop is at the time the cancel was sent")
        assertEquals(SENT_AT + ONE_HOUR, outcome.decidedAtMs)
    }

    @Test
    fun `a temp basal decided late carries the time it was sent`() = runTest {
        runningTempBasal()
        f.nowMs = SENT_AT
        f.pod.dropLinkWhileAnswering = 13 to 0

        assertFalse(tbr(2.0, 60).success)
        assertEquals(SENT_AT, f.decided.single().command.createdAtMs, "the cancel, decided at once")

        f.nowMs = SENT_AT + ONE_HOUR
        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.pump.getPumpStatus("decide the open temp basal")

        val late = f.decided.last()
        assertEquals(DashCommandKind.TEMP_BASAL, late.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, late.confirmation)
        assertTrue(late.decidedLate)
        assertEquals(SENT_AT, late.command.createdAtMs, "the start is at the time the temp basal was sent")
        assertEquals(SENT_AT + ONE_HOUR, late.decidedAtMs)
    }

    // ---------------------------------------------------------------- the new temp basal is not confirmed

    @Test
    fun `a lost answer to the new temp basal after a confirmed cancel leaves it open`() = runTest {
        runningTempBasal()
        f.pod.dropLinkWhileAnswering = 13 to 0

        val result = tbr(2.0, 60)

        assertFalse(result.success, result.comment)
        assertFalse(result.enacted)
        val cancel = f.decided.single()
        assertEquals(DashCommandKind.STOP_TEMP_BASAL, cancel.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, cancel.confirmation, "the cancel is reported on its own")
        assertFalse(cancel.decidedLate)
        val open = assertNotNull(f.stateManager.activeCommand, "the new temp basal stays open")
        assertEquals(DashCommandKind.TEMP_BASAL, open.kind)
        assertEquals(2.0, open.tempBasalRate)
        assertEquals(60, open.tempBasalMinutes)
        assertEquals(6, open.sequence)

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        val before = f.writesFromPump().size
        assertFalse(tbr(3.0, 30).success)
        assertFalse(f.pump.deliverTreatment(bolus(1.0)).success)
        assertFalse(f.pump.cancelTempBasal(false).success)
        assertEquals(before, f.writesFromPump().size, "nothing may be sent while the temp basal is open")

        f.pump.getPumpStatus("decide the open temp basal")

        val late = f.decided.last()
        assertEquals(DashCommandKind.TEMP_BASAL, late.command.kind)
        assertEquals(DashCommandConfirmation.SUCCESS, late.confirmation)
        assertTrue(late.decidedLate)
        assertNull(f.stateManager.activeCommand)
    }

    // ---------------------------------------------------------------- an open command before

    @Test
    fun `an open command blocks the cancel too and nothing is sent`() = runTest {
        runningTempBasal()
        f.pod.answerFor = { command ->
            // The bolus and the status reads that try to decide it get a NAK: the bolus stays open.
            if (command[6] == CommandType.PROGRAM_INSULIN.value && !isTempBasal(command)) parityHex(NAK)
            else if (command[6] == CommandType.GET_STATUS.value) parityHex(NAK)
            else defaultStatus(f.lastProgrammingSequence, 0, TEMP_BASAL_ACTIVE)
        }
        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success, "not decided: counted as asked, as Android")
        assertEquals(DashCommandKind.BOLUS, f.stateManager.activeCommand?.kind)
        val before = f.writesFromPump().size

        val result = tbr(2.0, 60)

        assertFalse(result.success)
        assertEquals(before, f.writesFromPump().size, "no cancel and no temp basal")
        assertEquals(DashCommandKind.BOLUS, f.stateManager.activeCommand?.kind, "the bolus stays open")
    }

    private companion object {

        /** The pod id the tests pair with, 0x02420001. */
        const val UNIQUE_ID = 37879809

        /** Android `NakResponseTest`: ILLEGAL_PARAM. */
        const val NAK = "0603070009"

        /** A wall clock time to send at: 2026-11-13 10:00 UTC. */
        const val SENT_AT = 1_794_564_000_000L

        const val ONE_HOUR = 3_600_000L
    }
}
