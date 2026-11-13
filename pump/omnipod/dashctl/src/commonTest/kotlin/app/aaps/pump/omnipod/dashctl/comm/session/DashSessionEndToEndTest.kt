package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.dashctl.comm.exceptions.PairingException
import app.aaps.pump.omnipod.dashctl.comm.exceptions.SessionEstablishmentException
import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.io.DashLinkSetupResult
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral.FinalAnswer
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.pair.LTKExchanger
import app.aaps.pump.omnipod.dashctl.comm.pair.PairResult
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.PodKeys
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.parity.parityAesCcm
import app.aaps.pump.omnipod.dashctl.parity.parityAesCmac
import app.aaps.pump.omnipod.dashctl.parity.parityAesEcb
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.parityX25519
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.definition.NakErrorType
import app.aaps.pump.omnipod.dashctl.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.NakResponse
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A whole conversation with a pod, end to end: the real [LTKExchanger], [SessionEstablisher] and
 * [Session], on the real `MessageIO` and `DashBleLink`, against [FakeDashPod] at the level of BLE
 * events.
 *
 * Android has no test like this. Each expectation names the Android source line that gives it.
 * The Android files are in `pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm`:
 * `pair/LTKExchanger.kt` (LTKExchanger), `session/SessionEstablisher.kt` (SessionEstablisher),
 * `session/Session.kt` (Session), `message/MessageIO.kt` (MessageIO) and `SharedBleManager.kt`
 * (SharedBleManager). The pod state is in `../pod/state/OmnipodDashPodStateManagerImpl.kt`.
 *
 * "Phone message n" is the n-th whole message the pod got on the link (see [FakeDashPod]). After a
 * fresh pairing it is the outer sequence number: 1-4 pairing, 5-6 session, 7 command, 8 ACK.
 */
class DashSessionEndToEndTest {

    private lateinit var pod: FakeDashPod
    private lateinit var messageIO: MessageIO
    private val ids = Ids(null)

    /**
     * The phone EAP SQN, as Android keeps it in the pod state: 1 for a new pod
     * (OmnipodDashPodStateManagerImpl line 1030), plus one before each session (line 724).
     */
    private var phoneEapSqn = 1L

    @AfterTest
    fun closePod() {
        if (!::pod.isInitialized) return
        pod.close()
        pod.assertNoProblems()
    }

    /**
     * A new pod and a prepared link. With [lateAnswers], the pod gives its final answer (SUCCESS or
     * FAIL) 150 ms after the write confirmation of the last packet, from another thread, as a real
     * pod does: MessageIO then reads it in `expectCommandType` (MessageIO line 90), not in
     * `peekForNack` (line 81).
     */
    private fun start(keys: PodKeys = PodKeys.AndroidVector, lateAnswers: Boolean = false) {
        pod = FakeDashPod(keys, otherThread = lateAnswers)
        if (lateAnswers) pod.central.finalAnswerTiming = FakeDashPodCentral.AnswerTiming.AFTER_CONFIRM_LATE
        messageIO = connect(pod.central)
    }

    private fun connect(central: FakeDashPodCentral): MessageIO {
        val link = DashBleLink(central, FakeDashPodCentral.POD_ID, SilentPodLogger)
        central.listener = link::onEvent
        assertEquals(DashLinkSetupResult.Ready, link.prepare())
        return MessageIO(SilentPodLogger, link.cmd, link.data)
    }

    /** The pod leaves and comes back: a new link to the same pod. */
    private fun reconnect() {
        messageIO = connect(pod.reconnect())
        pod.fromPhone.clear()
    }

    /** The phone keys of the Android KeyExchangeTest, so the pod can replay that vector. */
    private fun pair(): PairResult {
        val keyExchange = DashKeyExchange(
            x25519 = parityX25519(),
            aesCmac = parityAesCmac(),
            pdmNonce = parityHex(FakeDashPod.VECTOR_PDM_NONCE),
            pdmPrivate = parityHex(FakeDashPod.VECTOR_PDM_PRIVATE)
        )
        return LTKExchanger(SilentPodLogger, messageIO, ids, keyExchange).negotiateLTK()
    }

    private fun establish(ltk: ByteArray, sqn: Long, msgSeq: Byte): SessionNegotiationResponse =
        SessionEstablisher(
            aapsLogger = SilentPodLogger,
            msgIO = messageIO,
            ltk = ltk,
            eapSqn = EapSqn(sqn).value,
            ids = ids,
            msgSeq = msgSeq,
            aesEcb = parityAesEcb(),
            randomBytes = CountingRandomBytes()
        ).negotiateSessionKeys()

    /**
     * Mirror of the Android SharedBleManager.establishSession (lines 165-178): one session set up
     * with the next SQN (line 168), and after a resynchronization one more with the SQN of the
     * pod plus one (lines 171-172), with the SAME outer counter [msgSeq]. A second
     * resynchronization is an error (lines 173-175).
     */
    private fun establishLikeAndroid(ltk: ByteArray, msgSeq: Byte): SessionKeys {
        val first = establish(ltk, ++phoneEapSqn, msgSeq)
        if (first is SessionKeys) return first
        phoneEapSqn = assertIs<SessionNegotiationResynchronization>(first).synchronizedEapSqn.toLong()
        return assertIs<SessionKeys>(establish(ltk, ++phoneEapSqn, msgSeq), "Android throws on a second resynchronization")
    }

    private fun session(keys: SessionKeys) = Session(
        aapsLogger = SilentPodLogger,
        msgIO = messageIO,
        ids = ids,
        sessionKeys = keys,
        enDecrypt = EnDecrypt(SilentPodLogger, keys.nonce, keys.ck, parityAesCcm())
    )

    private fun getStatus(sequence: Int = 3) = GetStatusCommand.Builder()
        .setUniqueId(ids.podId.toLong().toInt())
        .setSequenceNumber(sequence.toShort())
        .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
        .build()

    /** Fresh pairing then session, as Android OmnipodDashBleManagerImpl.activate does (lines 74-78). */
    private fun pairAndOpenSession(): Session {
        val pairResult = pair()
        return session(establishLikeAndroid(pairResult.ltk, pairResult.msgSeq))
    }

    private fun wireSequences() = pod.fromPhone.map { it.sequenceNumber.toInt() }

    // ---------------------------------------------------------------- the good path

    @Test
    fun `fresh pairing then session then one command and its answer`() {
        start()
        val pairResult = pair()

        // Android KeyExchangeTest: both sides have its LTK, and the phone sent its pdmConf.
        assertEquals(FakeDashPod.VECTOR_LTK, pairResult.ltk.toParityHex())
        assertEquals(FakeDashPod.VECTOR_LTK, pod.ltk!!.toParityHex())
        assertEquals("535053323d0010" + FakeDashPod.VECTOR_PDM_CONF, pod.fromPhone[2].payload.toParityHex())
        // LTKExchanger line 27 (seq = 1) and the seq++ of lines 40, 54 and 68: the result is 4 (line 88).
        assertEquals(4, pairResult.msgSeq.toInt())

        val keys = establishLikeAndroid(pairResult.ltk, pairResult.msgSeq)
        // SessionEstablisher lines 41 and 58: the challenge is 5, SUCCESS is 6, and 6 is kept (line 68).
        assertEquals(6, keys.msgSequenceNumber.toInt())
        // First session after pairing uses SQN 2 (OmnipodDashPodStateManagerImpl lines 724 and 1030).
        assertEquals(2L, phoneEapSqn)
        assertEquals(2L, pod.lastSqn)
        assertEquals(pod.ck!!.toParityHex(), keys.ck.toParityHex())

        val session = session(keys)
        val command = getStatus()
        assertEquals(CommandSendSuccess, session.sendCommand(command))
        val received = assertIs<CommandReceiveSuccess>(session.readAndAckResponse())

        assertContentEquals(command.encoded, pod.commands.single())
        assertIs<DefaultStatusResponse>(received.result)
        assertEquals(FakeDashPod.DEFAULT_STATUS_RESPONSE, received.result.encoded.toParityHex())
        // Session line 41: the command is 7 (the pin firstCommandAfterFreshPairCarriesOuterSequenceSeven),
        // line 98: the ACK is 8.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), wireSequences())
        val (cmd, ack) = pod.fromPhone.takeLast(2)
        assertEquals(MessageType.ENCRYPTED.value, cmd.type)
        assertEquals(1, cmd.eqos) // Session line 154
        assertTrue(ack.ack)
        assertEquals(0, ack.eqos) // Session line 133
        assertEquals(0x0d, ack.ackNumber.toInt()) // Session line 135: pod sequence 0x0c plus one
        assertEquals(1, pod.acks.size)
        assertEquals(8, keys.msgSequenceNumber.toInt())
        // Nonce: command, answer, ACK, on both sides.
        assertEquals(3L, keys.nonce.sqn)
        assertEquals(3L, pod.nonce!!.sqn)
    }

    @Test
    fun `the fake pod answer has the layout of the Android capture`() {
        // EnDecryptTest.decrypt: 0.0= then unique id 08202ea9, sequence 7 and length 10 (1c0a),
        // a default status response and its CRC 01fe.
        assertEquals(
            "08202ea91c0a" + FakeDashPod.DEFAULT_STATUS_RESPONSE + "01fe",
            FakeDashPod.buildResponseData(parityHex("08202ea9"), 7, parityHex(FakeDashPod.DEFAULT_STATUS_RESPONSE)).toParityHex()
        )
    }

    @Test
    fun `a pod that computes its own keys ends with the same LTK as the phone`() {
        start(PodKeys.Generated())
        val pairResult = pair()

        assertEquals(pod.ltk!!.toParityHex(), pairResult.ltk.toParityHex())
        assertNotEquals(FakeDashPod.VECTOR_LTK, pairResult.ltk.toParityHex())
        // And the session works on that key.
        val keys = establishLikeAndroid(pairResult.ltk, pairResult.msgSeq)
        assertEquals(pod.ck!!.toParityHex(), keys.ck.toParityHex())
    }

    @Test
    fun `reconnect with the stored LTK starts the session at outer sequence one`() {
        start()
        val pairResult = pair()
        establishLikeAndroid(pairResult.ltk, pairResult.msgSeq)
        reconnect()

        // SharedBleManager line 154: establishSession(1). SessionEstablisher lines 41 and 58: 2 and 3.
        val keys = establishLikeAndroid(pairResult.ltk, 1)
        assertEquals(3, keys.msgSequenceNumber.toInt())
        // The next SQN, with no resynchronization (OmnipodDashPodStateManagerImpl line 724).
        assertEquals(3L, phoneEapSqn)
        assertEquals(3L, pod.lastSqn)
        // A new nonce: the counter starts again at 0 (SessionEstablisher line 66).
        assertEquals(0L, keys.nonce.sqn)

        val session = session(keys)
        assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
        assertIs<CommandReceiveSuccess>(session.readAndAckResponse())
        // Session lines 41 and 98: command 4, ACK 5.
        assertEquals(listOf(2, 3, 4, 5), wireSequences())
        assertEquals(1, pod.acks.size)
    }

    @Test
    fun `a pod with a higher SQN is resynchronized and the second challenge reuses the outer sequence`() {
        start()
        val pairResult = pair()
        establishLikeAndroid(pairResult.ltk, pairResult.msgSeq)
        pod.lastSqn = 0x15e
        reconnect()

        val keys = establishLikeAndroid(pairResult.ltk, 1)

        // SessionEstablisher lines 50-56 give the pod SQN, SharedBleManager line 171 stores it and
        // line 172 starts again with the next SQN and the same msgSeq 1: the challenge is 2 twice.
        assertEquals(listOf(2, 2, 3), wireSequences())
        assertEquals(0x15fL, phoneEapSqn)
        assertEquals(0x15fL, pod.lastSqn)
        assertEquals(3, keys.msgSequenceNumber.toInt())
        assertEquals(pod.ck!!.toParityHex(), keys.ck.toParityHex())
    }

    /**
     * The same keys and the same random values give the same bytes on the wire, every run. Not in
     * Android; this check came from the old `DashCommandSessionTest`.
     */
    @Test
    fun `the same keys and random values give the same bytes every run`() {
        fun run(): List<String> {
            phoneEapSqn = 1L
            start()
            val session = pairAndOpenSession()
            assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
            assertIs<CommandReceiveSuccess>(session.readAndAckResponse())
            val bytes = pod.fromPhone.map { it.bytes.toParityHex() }
            pod.close()
            pod.assertNoProblems()
            return bytes
        }

        val first = run()
        val second = run()

        assertEquals(8, first.size)
        assertEquals(first, second)
    }

    // ---------------------------------------------------------------- pairing failures

    @Test
    fun `pairing stops when the pod does not confirm SP1 SP2`() {
        start()
        pod.finalAnswerFor = { if (it == 1) FinalAnswer.NONE else FinalAnswer.SUCCESS }

        // MessageIO lines 90-95 (no SUCCESS), then LTKExchanger lines 38 and 95-96.
        assertFailsWith<PairingException> { pair() }
        assertEquals(1, pod.central.messagesFromPhone.size)
    }

    @Test
    fun `pairing stops when the link is lost while the pod sends SPS1`() {
        start()
        pod.dropLinkWhileAnswering = 2 to 1

        // LTKExchanger line 50: "Could not read SPS1".
        val e = assertFailsWith<PairingException> { pair() }
        assertEquals("Could not read SPS1", e.message)
    }

    @Test
    fun `pairing stops when the link is lost while the phone sends SPS2`() {
        start()
        pod.dropLinkOnPhoneMessage = 3 to Packet.FIRST

        // LTKExchanger lines 62 and 95-96.
        assertFailsWith<PairingException> { pair() }
        assertEquals(2, pod.central.messagesFromPhone.size)
    }

    @Test
    fun `a link lost while the pod sends P0 still gives the LTK`() {
        start()
        pod.dropLinkWhileAnswering = 4 to 1

        // LTKExchanger line 66 (no exception after SPS2) and lines 82-84 (only a warning for P0).
        val pairResult = pair()
        assertEquals(FakeDashPod.VECTOR_LTK, pairResult.ltk.toParityHex())
        assertEquals(4, pairResult.msgSeq.toInt())
    }

    // ---------------------------------------------------------------- session failures

    @Test
    fun `session set up stops when the pod does not confirm the challenge`() {
        start()
        val pairResult = pair()
        pod.finalAnswerFor = { if (it == 5) FinalAnswer.NONE else FinalAnswer.SUCCESS }

        // SessionEstablisher lines 43-46.
        assertFailsWith<SessionEstablishmentException> { establishLikeAndroid(pairResult.ltk, pairResult.msgSeq) }
        assertEquals(1L, pod.lastSqn)
    }

    @Test
    fun `a link lost while the pod sends RES stops the session and the next one needs no resynchronization`() {
        start()
        val pairResult = pair()
        pod.dropLinkWhileAnswering = 5 to 1

        // SessionEstablisher lines 47-48: "Could not establish session".
        val e = assertFailsWith<SessionEstablishmentException> { establishLikeAndroid(pairResult.ltk, pairResult.msgSeq) }
        assertEquals("Could not establish session", e.message)
        // The pod took SQN 2. The phone has 2 too: it was increased before the try (line 724).
        assertEquals(2L, pod.lastSqn)
        assertEquals(2L, phoneEapSqn)

        reconnect()
        val keys = establishLikeAndroid(pairResult.ltk, 1)
        assertEquals(listOf(2, 3), wireSequences())
        assertEquals(pod.ck!!.toParityHex(), keys.ck.toParityHex())
    }

    @Test
    fun `the phone has session keys even when the pod does not confirm the EAP SUCCESS`() {
        start()
        val pairResult = pair()
        pod.finalAnswerFor = { if (it == 6) FinalAnswer.NONE else FinalAnswer.SUCCESS }

        // SessionEstablisher line 60: the result of sending SUCCESS is not checked.
        val keys = establishLikeAndroid(pairResult.ltk, pairResult.msgSeq)
        assertEquals(6, keys.msgSequenceNumber.toInt())
        assertNull(pod.ck)
    }

    @Test
    fun `a link lost during the EAP SUCCESS gives keys but no command can be sent`() {
        start()
        val pairResult = pair()
        pod.dropLinkOnPhoneMessage = 6 to Packet.FIRST

        // SessionEstablisher line 60, as above.
        val keys = establishLikeAndroid(pairResult.ltk, pairResult.msgSeq)
        assertNull(pod.ck)
        // Session lines 45-63: five tries, all fail on the lost link.
        val result = session(keys).sendCommand(getStatus())
        assertEquals(CommandSendErrorSending("Maximum number of tries reached. Could not send command"), result)
        assertTrue(pod.commands.isEmpty())
    }

    // ---------------------------------------------------------------- command failures

    @Test
    fun `a command the pod does not confirm is an undefined state and is not sent again`() {
        start()
        val session = pairAndOpenSession()
        pod.finalAnswerFor = { if (it == 7) FinalAnswer.NONE else FinalAnswer.SUCCESS }

        // MessageIO lines 90-95 give MessageSendErrorConfirming, Session lines 52-54 return it.
        assertIs<CommandSendErrorConfirming>(session.sendCommand(getStatus()))
        assertEquals(7, pod.central.messagesFromPhone.size)
        assertEquals(7, session.sessionKeys.msgSequenceNumber.toInt())
    }

    @Test
    fun `after a FAIL from the pod the phone sends the same bytes again`() {
        start(lateAnswers = true)
        val session = pairAndOpenSession()
        pod.finalAnswerFor = { if (it == 7) FinalAnswer.FAIL else FinalAnswer.SUCCESS }

        // MessageIO lines 99-101 give MessageSendErrorSending, Session lines 57-58 try again with
        // the message built once at line 44: same bytes, same outer sequence 7, same nonce.
        assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
        val sent = pod.central.messagesFromPhone
        assertEquals(8, sent.size)
        assertContentEquals(sent[6], sent[7])
        assertEquals(1, pod.commands.size)
        assertIs<CommandReceiveSuccess>(session.readAndAckResponse())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), wireSequences())
    }

    @Test
    fun `the phone gives up after five FAIL answers`() {
        start(lateAnswers = true)
        val session = pairAndOpenSession()
        pod.finalAnswerFor = { if (it >= 7) FinalAnswer.FAIL else FinalAnswer.SUCCESS }

        // Session lines 45 (0..MAX_TRIES, MAX_TRIES = 4) and 62-63.
        val result = session.sendCommand(getStatus())
        assertEquals(CommandSendErrorSending("Maximum number of tries reached. Could not send command"), result)
        assertEquals(6 + 5, pod.central.messagesFromPhone.size)
        assertTrue(pod.commands.isEmpty())
        assertEquals(7, session.sessionKeys.msgSequenceNumber.toInt())
    }

    @Test
    fun `a NAK answer is given to the caller and acknowledged`() {
        start()
        val session = pairAndOpenSession()
        // Android NakResponseTest: ILLEGAL_PARAM.
        pod.answerFor = { parityHex("0603070009") }

        assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
        // Session lines 86-96: the type check is commented out, so a NAK is a success here, and
        // lines 98-105 acknowledge it.
        val received = assertIs<CommandReceiveSuccess>(session.readAndAckResponse())
        assertEquals(NakErrorType.ILLEGAL_PARAM, assertIs<NakResponse>(received.result).nakErrorType)
        assertEquals(1, pod.acks.size)
    }

    @Test
    fun `a broken MAC on the answer throws and no ACK is sent`() {
        start()
        val session = pairAndOpenSession()
        pod.breakAnswerMac = true

        assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
        // Session line 81: the decrypt throws (InvalidCipherTextException on Android) before the ACK.
        assertFailsWith<DashCryptoException> { session.readAndAckResponse() }
        assertTrue(pod.acks.isEmpty())
        assertEquals(7, session.sessionKeys.msgSequenceNumber.toInt())
        // The nonce moved for the command and for the failed decrypt (EnDecrypt.decrypt).
        assertEquals(2L, session.sessionKeys.nonce.sqn)
    }

    @Test
    fun `an ACK the pod does not confirm gives the answer with an ACK error`() {
        start()
        val session = pairAndOpenSession()
        pod.finalAnswerFor = { if (it == 8) FinalAnswer.NONE else FinalAnswer.SUCCESS }

        assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
        // Session lines 101-104.
        val received = assertIs<CommandAckError>(session.readAndAckResponse())
        assertIs<DefaultStatusResponse>(received.result)
        assertEquals(8, session.sessionKeys.msgSequenceNumber.toInt())
        assertTrue(pod.acks.isEmpty())
    }

    @Test
    fun `a link lost on the first packet of a command makes five failed tries`() {
        start()
        val session = pairAndOpenSession()
        pod.dropLinkOnPhoneMessage = 7 to Packet.FIRST

        // MessageIO lines 169-178: not the last packet, so an error while sending. Session lines
        // 57-58 try again, and every try fails on the lost link (lines 62-63).
        val result = session.sendCommand(getStatus())
        assertEquals(CommandSendErrorSending("Maximum number of tries reached. Could not send command"), result)
        assertTrue(pod.commands.isEmpty())
    }

    @Test
    fun `a link lost on the last packet of a command is an undefined state`() {
        start()
        val session = pairAndOpenSession()
        pod.dropLinkOnPhoneMessage = 7 to Packet.LAST

        // MessageIO lines 173-175: the last packet was not confirmed. Session lines 52-54.
        assertIs<CommandSendErrorConfirming>(session.sendCommand(getStatus()))
        assertEquals(6, pod.central.messagesFromPhone.size)
    }

    @Test
    fun `a link lost while the pod answers leaves an executed command without answer`() {
        start()
        val session = pairAndOpenSession()
        pod.dropLinkWhileAnswering = 7 to 1

        assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
        // Session lines 69-79: five reads, then "Could not read response".
        assertEquals(CommandReceiveError("Could not read response"), session.readAndAckResponse())
        assertEquals(1, pod.commands.size)
        assertEquals(7, session.sessionKeys.msgSequenceNumber.toInt())
    }

    @Test
    fun `a link lost during the ACK gives the answer with an ACK error`() {
        start()
        val session = pairAndOpenSession()
        pod.dropLinkOnPhoneMessage = 8 to Packet.FIRST

        assertEquals(CommandSendSuccess, session.sendCommand(getStatus()))
        // Session lines 101-104.
        val received = assertIs<CommandAckError>(session.readAndAckResponse())
        assertIs<DefaultStatusResponse>(received.result)
        assertTrue(pod.acks.isEmpty())
    }
}
