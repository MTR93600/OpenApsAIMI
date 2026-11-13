package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral.FinalAnswer
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The command flow of [IosDashPump] on the ported Android stack, against a simulated pod at the
 * level of BLE events: the two counters, the answer of each command, and the decision on the
 * active command.
 *
 * Android references: `SharedBleManager.sendCommand` and `establishSession`,
 * `OmnipodDashManagerImpl.PodEventInterceptor` (the 4-bit sequence),
 * `OmnipodDashPodStateManagerImpl.getCommandConfirmationFromState` (the decision).
 *
 * "Phone message n" is the n-th whole message the pod got on the link. After a fresh pairing it
 * is the outer sequence: 1-4 pairing, 5-6 session, 7 the first command, 8 its ACK.
 */
class IosDashPumpCommandTest {

    private val f = DashPumpOnFakePod()

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolus(units: Double) = DetailedBolusInfo().apply { insulin = units }

    private fun wireSequences() = f.pod.fromPhone.map { it.sequenceNumber.toInt() }

    // ---------------------------------------------------------------- the good path

    @Test
    fun `a bolus confirmed by the pod status is reported as delivered`() = runTest {
        f.pairAndConnect()

        val result = f.pump.deliverTreatment(bolus(5.0))

        assertTrue(result.success, result.comment)
        assertTrue(result.enacted)
        assertEquals(5.0, result.bolusDelivered)
        assertNull(f.stateManager.activeCommand, "the status closed the command")
        assertEquals(0, f.stateManager.podStatus?.lastProgrammingCommandSequence)
        // Built with 0, +1 when sent, +1 when the answer was read (PodEventInterceptor). Then the
        // bolus wait reads one status (Android `waitForBolusDeliveryToComplete`): +2 again.
        assertEquals(4, f.stateManager.messageSequenceNumber)
        // Bolus 7, its ACK 8, the status of the bolus wait 9, its ACK 10.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), wireSequences())
        assertEquals(2, f.pod.commands.size)
        assertEquals(2, f.pod.acks.size)
    }

    @Test
    fun `each command takes the next command sequence and the next outer sequence`() = runTest {
        f.pairAndConnect()

        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)
        assertTrue(f.pump.setTempBasalAbsolute(1.5, 30, false, PumpSync.TemporaryBasalType.NORMAL).success)

        // Bolus 0, the status of the bolus wait 2, the temp basal 4.
        val sequences = f.pod.commands.map { (it[4].toInt() and 0xff shr 2) and 0x0f }
        assertEquals(listOf(0, 2, 4), sequences)
        assertEquals(6, f.stateManager.messageSequenceNumber)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), wireSequences())
    }

    @Test
    fun `a temp basal confirmed by the pod status is reported as enacted`() = runTest {
        f.pairAndConnect()

        val result = f.pump.setTempBasalAbsolute(1.5, 30, false, PumpSync.TemporaryBasalType.NORMAL)

        assertTrue(result.success, result.comment)
        assertEquals(1.5, result.absolute)
        assertEquals(30, result.duration)
        assertNull(f.stateManager.activeCommand)
    }

    @Test
    fun `an answer whose ACK the pod does not confirm still decides the command`() = runTest {
        f.pairAndConnect()
        f.pod.finalAnswerFor = { if (it == 8) FinalAnswer.NONE else FinalAnswer.SUCCESS }
        // The pod did not take the ACK, so it and the phone no longer agree on the nonce, and a
        // status read of the bolus wait on this session would wait for an answer that never comes.
        // The link drops at the first status read instead, so the wait ends at once.
        f.pod.dropLinkOnPhoneMessage = 9 to Packet.FIRST

        val result = f.pump.deliverTreatment(bolus(2.0))

        // SharedBleManager: CommandAckError is handled like CommandReceiveSuccess.
        assertTrue(result.success, result.comment)
        assertEquals(2.0, result.bolusDelivered)
        assertEquals(DashCommandConfirmation.SUCCESS, f.decided.single().confirmation)
        // The wait reports the asked amount when it cannot read a status (Android "updated later"),
        // never more, and sends no second bolus.
        assertEquals(1, f.bolusCommands())
    }

    // ---------------------------------------------------------------- not taken, not sent

    @Test
    fun `a bolus the pod status shows as not taken is denied and nothing is delivered`() = runTest {
        f.pairAndConnect()
        f.pod.answerFor = { DashPumpOnFakePod.defaultStatus(lastProgrammingSequence = 9) }

        val result = f.pump.deliverTreatment(bolus(5.0))

        assertFalse(result.success)
        assertEquals(0.0, result.bolusDelivered)
        assertNull(f.stateManager.activeCommand, "DENIED is final")
        assertTrue(result.comment.contains("refused"), result.comment)
    }

    @Test
    fun `a bolus that never left the phone is closed and nothing is delivered`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkOnPhoneMessage = 7 to Packet.FIRST

        val result = f.pump.deliverTreatment(bolus(5.0))

        // Session gives CommandSendErrorSending after five tries: the pod cannot have it.
        assertFalse(result.success)
        assertEquals(0.0, result.bolusDelivered)
        assertNull(f.stateManager.activeCommand, "SENDING_FAILURE is final")
        assertEquals(0, f.stateManager.messageSequenceNumber, "the sequence moves only when the command was sent")
        assertTrue(f.pod.commands.isEmpty())
    }

    // ---------------------------------------------------------------- not confirmed

    @Test
    fun `a bolus sent but not confirmed stays open and blocks the next command`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkOnPhoneMessage = 7 to Packet.LAST

        val result = f.pump.deliverTreatment(bolus(5.0))

        // CommandSendErrorConfirming: the pod may have it. Android moves the sequence on as for a sent command.
        assertFalse(result.success)
        assertEquals(0.0, result.bolusDelivered)
        val open = assertNotNull(f.stateManager.activeCommand, "the command must stay open")
        assertEquals(DashCommandKind.BOLUS, open.kind)
        assertTrue(open.sent)
        assertEquals(1, f.stateManager.messageSequenceNumber)

        val before = f.writesFromPump().size
        assertFalse(f.pump.deliverTreatment(bolus(5.0)).success)
        assertFalse(f.pump.cancelTempBasal(false).success)
        assertEquals(before, f.writesFromPump().size, "nothing may be sent while the bolus is open")
    }

    @Test
    fun `a NAK leaves the bolus open until a status decides it`() = runTest {
        f.pairAndConnect()
        // Android NakResponseTest: ILLEGAL_PARAM.
        f.pod.answerFor = { parityHex("0603070009") }

        val result = f.pump.deliverTreatment(bolus(5.0))

        assertFalse(result.success)
        assertEquals(0.0, result.bolusDelivered)
        assertNotNull(f.stateManager.activeCommand, "a NAK is not a status: the command stays open")
        assertEquals(2, f.stateManager.messageSequenceNumber, "sent and answered")
        val before = f.writesFromPump().size
        assertFalse(f.pump.deliverTreatment(bolus(5.0)).success)
        assertEquals(before, f.writesFromPump().size, "the open bolus blocks a new one")

        // The pod did not take it: its status still shows no programming command.
        f.answerWithStatus()
        f.pump.getPumpStatus("check the open bolus")

        assertNull(f.stateManager.activeCommand, "the status decided the command (DENIED)")
        assertEquals(4, f.stateManager.messageSequenceNumber)
        val next = f.pump.deliverTreatment(bolus(1.0))
        assertTrue(next.success, next.comment)
        assertEquals(4, f.pod.commands.last { DashPumpOnFakePod.isBolus(it) }[4].toInt() and 0xff shr 2 and 0x0f, "built with the stored sequence")
    }

    @Test
    fun `a status read that cannot be sent does not close an open command`() = runTest {
        f.pairAndConnect()
        f.pod.answerFor = { parityHex("0603070009") }
        f.pump.deliverTreatment(bolus(5.0))
        assertNotNull(f.stateManager.activeCommand)

        // The bolus took messages 7 and 8: the status read is message 9.
        f.pod.dropLinkOnPhoneMessage = 9 to Packet.FIRST
        f.pump.getPumpStatus("link lost")

        val open = assertNotNull(f.stateManager.activeCommand, "a failed status read must not mark the bolus not sent")
        assertTrue(open.sent)
    }

    // ---------------------------------------------------------------- pulse rate

    @Test
    fun `a bolus is refused while the pulse rate of the pod is not known`() = runTest {
        f.pairAndConnect(withPulseRate = false)
        val before = f.writesFromPump().size

        val result = f.pump.deliverTreatment(bolus(1.0))

        assertFalse(result.success)
        assertTrue(result.comment.contains("pulse rate"), result.comment)
        assertEquals(before, f.writesFromPump().size, "nothing sent")
        assertNull(f.stateManager.activeCommand, "nothing opened")
    }

    // ---------------------------------------------------------------- pairing and session

    @Test
    fun `pairing without an address gives the pod the Android id`() {
        f.pump.pair()

        // Ids.podId when the pod has no unique id: controller id 4242 (0x1092) plus one, 0x1091.
        assertEquals(0x1091L, f.stateManager.uniqueId)
        assertEquals(Id(parityHex("00001091")), f.pod.podId)
    }

    @Test
    fun `pairing a pod that is already paired does nothing`() = runTest {
        f.pairAndConnect()
        val ltk = f.stateManager.ltk
        val before = f.writesFromPump().size

        f.pump.pair(parityHex("02420002"))

        assertEquals(before, f.writesFromPump().size)
        assertContentEquals(ltk, f.stateManager.ltk)
        assertEquals(0x02420001L, f.stateManager.uniqueId)
    }

    @Test
    fun `a session after a reconnect starts the outer counter at one`() = runTest {
        f.pairAndConnect()
        assertSame(DashConnectResult.SessionReady, f.reconnect(), "the connection sets up the session")
        assertTrue(f.pump.deliverTreatment(bolus(1.0)).success)

        // SharedBleManager.connect: establishSession(1). Challenge 2, SUCCESS 3, command 4, ACK 5,
        // then the status of the bolus wait 6 and its ACK 7.
        assertEquals(listOf(2, 3, 4, 5, 6, 7), wireSequences())
        assertEquals(3L, f.stateManager.eapAkaSequenceNumber)
    }

    @Test
    fun `a pod with a higher SQN is resynchronized with the same outer counter`() = runTest {
        f.pump.pair(DashPumpOnFakePod.POD_ADDRESS)
        f.pod.lastSqn = 0x15e

        f.pump.establishSession()
        f.pump.connect("test")

        // SharedBleManager lines 168-172: the second challenge reuses msgSeq 4, so it is 5 again.
        assertEquals(listOf(1, 2, 3, 4, 5, 5, 6), wireSequences())
        assertEquals(0x15fL, f.stateManager.eapAkaSequenceNumber)
        assertEquals(0x15fL, f.pod.lastSqn)
        f.pump.getPumpStatus("session works")
        assertNotNull(f.stateManager.podStatus)
    }

    @Test
    fun `the stored SQN does not move when the session set up fails`() = runTest {
        f.pump.pair(DashPumpOnFakePod.POD_ADDRESS)
        f.pod.wrongRes = true

        val failed = runCatching { f.pump.establishSession() }

        assertTrue(failed.isFailure)
        assertFalse(f.pump.isInitialized())
        // The SQN is increased in memory before the try and stored only by commit (Android lines 723-730).
        assertEquals(1L, f.store.saved?.eapAkaSequenceNumber)
    }

    // ---------------------------------------------------------------- late outcome

    /**
     * The pod takes the bolus but its answer is lost, so the call reports a failure with nothing
     * delivered. A status on the next connection decides SUCCESS: the 5 U must be reported, or a
     * retry of the "failed" bolus would deliver it twice. Android: `handleCommandConfirmation`
     * writes it to `PumpSync`.
     */
    @Test
    fun `a bolus decided late by a status is reported as delivered`() = runTest {
        f.pairAndConnect()
        f.pod.dropLinkWhileAnswering = 7 to 0

        val result = f.pump.deliverTreatment(bolus(5.0))

        assertFalse(result.success)
        assertEquals(0.0, result.bolusDelivered)
        assertTrue(f.decided.isEmpty(), "nothing decided yet")
        assertNull(f.pump.lastBolusAmount.value)

        f.pod.dropLinkWhileAnswering = null
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.pump.getPumpStatus("decide the open bolus")

        assertNull(f.stateManager.activeCommand, "the status decided the bolus")
        val outcome = f.decided.single()
        assertEquals(DashCommandConfirmation.SUCCESS, outcome.confirmation)
        assertTrue(outcome.decidedLate)
        assertEquals(DashCommandKind.BOLUS, outcome.command.kind)
        assertEquals(5.0, outcome.command.requestedBolusUnits)
        assertEquals(5.0, f.pump.lastBolusAmount.value?.cU)
        assertEquals(1_000L, f.pump.lastBolusTime.value)
    }

    @Test
    fun `a bolus confirmed at once is given to the outcome receiver`() = runTest {
        f.pairAndConnect()

        assertTrue(f.pump.deliverTreatment(bolus(2.0)).success)

        val outcome = f.decided.single()
        assertEquals(DashCommandConfirmation.SUCCESS, outcome.confirmation)
        assertFalse(outcome.decidedLate)
        assertEquals(2.0, f.pump.lastBolusAmount.value?.cU)
    }

    // ---------------------------------------------------------------- 4-bit sequence wrap

    /**
     * Each answered exchange moves the 4-bit sequence by 2, so after 8 of them it is back to the
     * sequence of the last programming command. A bolus built with it, which the pod does not take,
     * would be decided SUCCESS by the next status (Android `getCommandConfirmationFromState`
     * compares the sequence only). The pump reads a status first, so the bolus gets another sequence
     * and the status decides DENIED.
     */
    @Test
    fun `a bolus is never built with the last programming sequence of the pod`() = runTest {
        f.pairAndConnect()
        // No status is known yet, so the TBR is preceded by a cancel (Android `observeNoActiveTempBasal`):
        // the cancel is built with 0, the TBR with 2.
        assertTrue(f.pump.setTempBasalAbsolute(1.0, 30, false, PumpSync.TemporaryBasalType.NORMAL).success)
        repeat(7) { f.pump.getPumpStatus("routine") }
        assertEquals(2, f.stateManager.messageSequenceNumber, "the sequence wrapped")
        assertEquals(2, f.stateManager.podStatus?.lastProgrammingCommandSequence, "the TBR was built with 2")
        // From now on the pod does not take a bolus: it answers with a NAK (Android NakResponseTest).
        f.pod.answerFor = { command ->
            if (command[6] == CommandType.PROGRAM_INSULIN.value) parityHex("0603070009")
            else DashPumpOnFakePod.defaultStatus(f.lastProgrammingSequence)
        }

        val result = f.pump.deliverTreatment(bolus(5.0))

        assertFalse(result.success)
        val open = assertNotNull(f.stateManager.activeCommand)
        assertTrue(open.sequence != 2, "the bolus must not take the sequence of the TBR")
        f.pump.getPumpStatus("decide the open bolus")
        assertNull(f.stateManager.activeCommand)
        assertEquals(DashCommandConfirmation.DENIED, f.decided.last().confirmation)
        assertNull(f.pump.lastBolusAmount.value, "the pod never took the bolus")
    }

    // ---------------------------------------------------------------- errors close the link

    /**
     * Android `SharedBleManager`: an exception in the session set up disconnects, and the next
     * session is on a new connection. A second challenge on the same link could read the late answer
     * to the first one as its own.
     */
    @Test
    fun `a failed session set up closes the link and a second try sends nothing`() = runTest {
        f.pump.pair(DashPumpOnFakePod.POD_ADDRESS)
        f.pod.wrongRes = true
        assertTrue(runCatching { f.pump.establishSession() }.isFailure)
        f.pod.wrongRes = false
        val before = f.writesFromPump().size

        val second = runCatching { f.pump.establishSession() }

        assertTrue(second.exceptionOrNull() is DashPumpNotPairedException, "$second")
        assertEquals(before, f.writesFromPump().size, "no second challenge on the same link")

        // A new connection works.
        assertSame(DashConnectResult.SessionReady, f.reconnect())
        f.pump.getPumpStatus("new link")
        assertNotNull(f.stateManager.podStatus)
    }

    /**
     * `MessageIO.sendMessage` throws when an RTS of the pod is already waiting. Android
     * `SharedBleManager.sendCommand` catches it, disconnects and reports an error. The call must
     * return a failure (not throw), keep the bolus open (some bytes may have gone) and close the link.
     */
    @Test
    fun `an exception while sending is a failure that keeps the bolus open and closes the link`() = runTest {
        f.pairAndConnect()
        val podMessage = MessagePacket(
            type = MessageType.ENCRYPTED,
            source = Id(DashPumpOnFakePod.POD_ADDRESS),
            destination = Id(parityHex("00001092")),
            payload = ByteArray(10),
            sequenceNumber = 1,
        )
        f.pod.central.podSendsMessage(podMessage.asByteArray())
        // The RTS is delivered on another thread: let it reach the link first.
        withContext(Dispatchers.Default) { delay(200) }

        val result = f.pump.deliverTreatment(bolus(5.0))

        assertFalse(result.success, result.comment)
        assertEquals(0.0, result.bolusDelivered)
        val open = assertNotNull(f.stateManager.activeCommand, "the bolus may have reached the pod")
        assertTrue(open.sent)
        assertEquals(0, f.stateManager.messageSequenceNumber, "Android moves the sequence only on CommandSent")
        assertFalse(f.pump.isConnected())
        assertFalse(f.pump.isInitialized())
        val before = f.writesFromPump().size
        f.pump.getPumpStatus("same link")
        assertFalse(f.pump.cancelTempBasal(false).success)
        assertEquals(before, f.writesFromPump().size, "nothing more on a closed link")
    }
}
