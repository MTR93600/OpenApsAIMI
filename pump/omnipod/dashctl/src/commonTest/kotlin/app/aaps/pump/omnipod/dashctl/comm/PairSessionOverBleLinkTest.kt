package app.aaps.pump.omnipod.dashctl.comm

import app.aaps.pump.omnipod.dashctl.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.dashctl.comm.exceptions.MessageIOException
import app.aaps.pump.omnipod.dashctl.comm.exceptions.SessionEstablishmentException
import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.io.DashLinkSetupResult
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.pair.LTKExchanger
import app.aaps.pump.omnipod.dashctl.comm.pair.PairResult
import app.aaps.pump.omnipod.dashctl.comm.session.CommandReceiveSuccess
import app.aaps.pump.omnipod.dashctl.comm.session.CommandSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.session.CountingRandomBytes
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import app.aaps.pump.omnipod.dashctl.comm.session.Session
import app.aaps.pump.omnipod.dashctl.comm.session.SessionEstablisher
import app.aaps.pump.omnipod.dashctl.comm.session.SessionKeys
import app.aaps.pump.omnipod.dashctl.comm.session.SessionNegotiationResynchronization
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.parity.parityAesCcm
import app.aaps.pump.omnipod.dashctl.parity.parityAesCmac
import app.aaps.pump.omnipod.dashctl.parity.parityAesEcb
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.parityX25519
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pairing ([LTKExchanger]), session set up ([SessionEstablisher]) and one command with its answer
 * and ACK ([Session]), through the real `MessageIO` and `DashBleLink`, against [FakeDashPod].
 *
 * Android has no test for these classes. The expected values come from the Android source of
 * `LTKExchanger`, `SessionEstablisher` and `Session` (pump/omnipod/common/.../comm/pair and
 * comm/session): the order of the messages, their sequence numbers, and the outer counter.
 */
class PairSessionOverBleLinkTest {

    private val pod = FakeDashPod()
    private val central = pod.central
    private val link = DashBleLink(central, FakeDashPodCentral.POD_ID, SilentPodLogger).also { central.listener = it::onEvent }
    private val messageIO = MessageIO(SilentPodLogger, link.cmd, link.data)
    private val ids = Ids(null)

    init {
        assertEquals(DashLinkSetupResult.Ready, link.prepare())
    }

    @AfterTest
    fun closePod() {
        pod.close()
        pod.assertNoProblems()
    }

    private fun pair(): PairResult {
        val keyExchange = DashKeyExchange(
            x25519 = parityX25519(),
            aesCmac = parityAesCmac(),
            pdmNonce = parityHex(FakeDashPod.VECTOR_PDM_NONCE),
            pdmPrivate = parityHex(FakeDashPod.VECTOR_PDM_PRIVATE)
        )
        return LTKExchanger(SilentPodLogger, messageIO, ids, keyExchange).negotiateLTK()
    }

    private fun establish(ltk: ByteArray, eapSqn: String, msgSeq: Byte, random: CountingRandomBytes = CountingRandomBytes()) =
        SessionEstablisher(
            aapsLogger = SilentPodLogger,
            msgIO = messageIO,
            ltk = ltk,
            eapSqn = parityHex(eapSqn),
            ids = ids,
            msgSeq = msgSeq,
            aesEcb = parityAesEcb(),
            randomBytes = random
        ).negotiateSessionKeys()

    // ---------------------------------------------------------------- pairing

    @Test
    fun `pairing sends the four Android messages and gives the pod LTK`() {
        val result = pair()

        assertEquals(pod.ltk!!.toParityHex(), result.ltk.toParityHex())
        // Android: seq starts at 1 and goes up before SPS1, SPS2 and SP0GP0.
        assertEquals(4, result.msgSeq.toInt())
        assertEquals(listOf(1, 2, 3, 4), pod.fromPhone.map { it.sequenceNumber.toInt() })
        assertTrue(pod.fromPhone.all { it.type == MessageType.PAIRING.value })
        assertTrue(pod.fromPhone.all { it.source.address.toParityHex() == "00001092" })
        assertTrue(pod.fromPhone.all { it.destination.address.toParityHex() == "fffffffe" })
        // SP1= our pod id (4242 + 1, low bits set to 01), SP2= the GetPodStatus bytes of Android.
        assertEquals(
            "5350313d" + "0004" + "00001091" + "2c5350323d" + "000b" + "ffc32dbd08030e0100008a",
            pod.fromPhone[0].payload.toParityHex()
        )
        // SPS1= our public key and nonce: the Android KeyExchangeTest values.
        assertEquals(
            "535053313d" + "0030" + "f2b6940243aba536a66e19fb9a39e37f1e76a1cd50ab59b3e05313b4fc93975e" + "edfdacb242c7f4e1d2bc4d93ca3c5706",
            pod.fromPhone[1].payload.toParityHex()
        )
        assertEquals("535053323d0010", pod.fromPhone[2].payload.toParityHex().substring(0, 14))
        assertEquals("5350302c475030", pod.fromPhone[3].payload.toParityHex())
    }

    @Test
    fun `pairing stops when the pod confirmation is wrong`() {
        pod.wrongPodConf = true
        assertFailsWith<MessageIOException> { pair() }
        // Android stops before SP0GP0.
        assertEquals(3, pod.fromPhone.size)
    }

    // ---------------------------------------------------------------- session

    @Test
    fun `session set up gives the pod CK and moves the outer counter by two`() {
        pod.storePairing(parityHex("341e16d13f1cbf73b19d1c2964fee02b"), ids.podId)
        val random = CountingRandomBytes()

        val keys = assertIs<SessionKeys>(establish(pod.ltk!!, "000000000002", msgSeq = 4, random = random))

        assertEquals(pod.ck!!.toParityHex(), keys.ck.toParityHex())
        assertEquals(6, keys.msgSequenceNumber.toInt())
        // Random draws in the Android order: identifier (1 byte), RAND (16), our IV (4).
        assertEquals(listOf(1, 16, 4), random.drawn.map { it.size })
        assertEquals(random.drawn[2].toParityHex() + "b0b1b2b3", keys.nonce.prefix.toParityHex())
        assertEquals(0L, keys.nonce.sqn)
        // Challenge with seq 5, then SUCCESS with seq 6: code 3, our identifier, size 4.
        assertEquals(listOf(5, 6), pod.fromPhone.map { it.sequenceNumber.toInt() })
        assertTrue(pod.fromPhone.all { it.type == MessageType.SESSION_ESTABLISHMENT.value })
        assertTrue(pod.fromPhone.all { it.destination.address.toParityHex() == "00001091" })
        assertEquals("03" + random.drawn[0].toParityHex() + "0004", pod.fromPhone[1].payload.toParityHex())
    }

    @Test
    fun `pod with another SQN gives a resynchronization`() {
        pod.storePairing(parityHex("341e16d13f1cbf73b19d1c2964fee02b"), ids.podId)
        pod.lastSqn = 0x15e

        val result = assertIs<SessionNegotiationResynchronization>(establish(pod.ltk!!, "000000000002", msgSeq = 4))

        assertEquals("00000000015e", result.synchronizedEapSqn.value.toParityHex())
        assertEquals(5, result.msgSequenceNumber.toInt())
        // No SUCCESS after a resynchronization.
        assertEquals(1, pod.fromPhone.size)
    }

    @Test
    fun `a wrong RES is refused`() {
        pod.storePairing(parityHex("341e16d13f1cbf73b19d1c2964fee02b"), ids.podId)
        pod.wrongRes = true
        assertFailsWith<SessionEstablishmentException> { establish(pod.ltk!!, "000000000002", msgSeq = 4) }
        assertEquals(1, pod.fromPhone.size)
    }

    // ---------------------------------------------------------------- the whole flow

    @Test
    fun `pair then session then one command with its answer and ACK`() {
        val pairResult = pair()
        val keys = assertIs<SessionKeys>(establish(pairResult.ltk, "000000000002", pairResult.msgSeq))
        pod.fromPhone.clear()
        val session = Session(
            aapsLogger = SilentPodLogger,
            msgIO = messageIO,
            ids = ids,
            sessionKeys = keys,
            enDecrypt = EnDecrypt(SilentPodLogger, keys.nonce, keys.ck, parityAesCcm())
        )
        val command = GetStatusCommand.Builder()
            .setUniqueId(4241)
            .setSequenceNumber(3.toShort())
            .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
            .build()

        assertEquals(CommandSendSuccess, session.sendCommand(command))
        val received = assertIs<CommandReceiveSuccess>(session.readAndAckResponse())

        // The pod got exactly the command bytes, inside S0.0=...,G0.0.
        assertContentEquals(command.encoded, pod.commands.single())
        // The answer is the Android capture: a default status response.
        assertIs<DefaultStatusResponse>(received.result)
        assertEquals("1d050016b00000000bff", received.result.encoded.toParityHex())
        // Outer counter: 6 after the session, 7 for the command, 8 for the ACK.
        val (cmd, ack) = pod.fromPhone
        assertEquals(7, cmd.sequenceNumber.toInt())
        assertEquals(1, cmd.eqos)
        assertEquals(MessageType.ENCRYPTED.value, cmd.type)
        assertEquals(8, ack.sequenceNumber.toInt())
        assertTrue(ack.ack)
        assertEquals(0, ack.eqos)
        // ACK number: the pod sequence (0x0c) plus one.
        assertEquals(0x0d, ack.ackNumber.toInt())
        assertEquals(1, pod.acks.size)
        assertEquals(8, keys.msgSequenceNumber.toInt())
        // Nonce: command, answer, ACK.
        assertEquals(3L, keys.nonce.sqn)
        assertEquals(3L, pod.nonce!!.sqn)
    }
}
