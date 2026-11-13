package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.io.DashLinkSetupResult
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.session.CountingRandomBytes
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import app.aaps.pump.omnipod.dashctl.comm.session.Session
import app.aaps.pump.omnipod.dashctl.comm.session.SessionEstablisher
import app.aaps.pump.omnipod.dashctl.comm.session.SessionKeys
import app.aaps.pump.omnipod.dashctl.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.CapturedCommandPacket
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.DecryptResponse
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.EncryptCommand
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Message envelope and counter parity.
 *
 * Part 1: the Android `MessagePacket` vectors, on the ported `MessagePacket`.
 *
 * Part 2: the two counters. Android keeps the outer packet counter in the session
 * (`SessionKeys.msgSequenceNumber`, a full byte) and the inner 4-bit command counter in the pod
 * state. These used to fail when dashctl had one counter for both. Do not change the expected
 * values. The pump level checks (wrapper, flags, nonce, pulse rate, unique id) live in
 * `IosDashPumpAndroidParityTest` in iosTest, because the pump is in iosMain.
 */
class AndroidEnvelopeParityTest {

    // -- Part 1: the envelope ------------------------------------------------------------------

    /** Android source: `MessagePacketTest.testParseMessagePacket`. */
    @Test
    fun envelopeParsesTheAndroidRealCapture() {
        val msg = MessagePacket.parse(parityHex(CapturedCommandPacket.WIRE_HEX))

        assertEquals(MessageType.ENCRYPTED, msg.type)
        assertEquals(CapturedCommandPacket.SOURCE, msg.source.toLong())
        assertEquals(CapturedCommandPacket.DESTINATION, msg.destination.toLong())
        assertEquals(CapturedCommandPacket.SEQUENCE_NUMBER, msg.sequenceNumber.toInt())
        assertEquals(CapturedCommandPacket.ACK_NUMBER, msg.ackNumber.toInt())
        assertEquals(CapturedCommandPacket.EQOS, msg.eqos.toInt())
        assertEquals(CapturedCommandPacket.SAS, msg.sas)
        assertEquals(CapturedCommandPacket.TFS, msg.tfs)
        assertEquals(CapturedCommandPacket.PRIORITY, msg.priority)
        assertEquals(CapturedCommandPacket.LAST_MESSAGE, msg.lastMessage)
        assertEquals(CapturedCommandPacket.GATEWAY, msg.gateway)
        assertEquals(CapturedCommandPacket.VERSION, msg.version.toInt())
        assertEquals(CapturedCommandPacket.PAYLOAD_HEX, msg.payload.toParityHex())
    }

    /** Android source: `MessagePacketTest.testSerializeMessagePacket`. */
    @Test
    fun envelopeSerialisesToTheAndroidRealCapture() {
        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            source = Id.fromLong(CapturedCommandPacket.SOURCE),
            destination = Id.fromLong(CapturedCommandPacket.DESTINATION),
            payload = parityHex(CapturedCommandPacket.PAYLOAD_HEX),
            sequenceNumber = CapturedCommandPacket.SEQUENCE_NUMBER.toByte(),
            eqos = CapturedCommandPacket.EQOS.toShort()
        )

        val bytes = msg.asByteArray()

        // Flags 11 01, sequence 07, size field 0340 = 26 bytes: the 8 byte MAC is not counted.
        assertEquals(CapturedCommandPacket.HEADER_HEX, bytes.copyOfRange(0, 16).toParityHex())
        assertEquals(CapturedCommandPacket.WIRE_HEX, bytes.toParityHex())
    }

    /** Android source: `EnDecryptTest.encrypt`. The header it signs as associated data. */
    @Test
    fun commandEnvelopeHeaderForEncryptionMatchesAndroid() {
        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            source = Id(parityHex("02420000")),
            destination = Id(parityHex("02420001")),
            payload = parityHex(EncryptCommand.WRAPPED_COMMAND_HEX),
            sequenceNumber = 7,
            eqos = 1
        )

        // The size field counts the 26 byte plaintext when forEncryption.
        assertEquals(EncryptCommand.HEADER_HEX, msg.asByteArray(forEncryption = true).copyOfRange(0, 16).toParityHex())
    }

    /** Android source: `EnDecryptTest.decrypt`. A pod answer header: ack bit, last message bit. */
    @Test
    fun podResponseEnvelopeParsesLikeAndroid() {
        val msg = MessagePacket.parse(parityHex(DecryptResponse.WIRE_HEX))

        assertTrue(msg.ack, "ack")
        assertTrue(msg.lastMessage, "last message")
        assertEquals(12, msg.sequenceNumber.toInt())
        assertEquals(22, msg.ackNumber.toInt())
        // Size field 0x0300 = 24 << 5: the plaintext size. The payload also holds the 8 byte MAC.
        assertEquals(24, msg.payload.size - 8)
        assertEquals(DecryptResponse.HEADER_HEX, msg.asByteArray().copyOfRange(0, 16).toParityHex())
    }

    // -- Part 2: the two counters ----------------------------------------------------------------

    private class MemoryStore : DashPodStateStore {
        var saved: DashPodState? = null
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saved = state
        }

        override fun clear() {
            saved = null
        }
    }

    private val ltk = parityHex("00112233445566778899aabbccddeeff")
    private val uniqueId = 0x02420001L
    private var pod: FakeDashPod? = null

    @AfterTest
    fun closePod() {
        pod?.close()
        pod?.assertNoProblems()
    }

    /** A pod paired before, on a prepared link. */
    private fun pairedPod(): MessageIO {
        val p = FakeDashPod().also { pod = it }
        p.storePairing(ltk, Id.fromLong(uniqueId))
        val link = DashBleLink(p.central, FakeDashPodCentral.POD_ID, SilentPodLogger)
        p.central.listener = link::onEvent
        assertEquals(DashLinkSetupResult.Ready, link.prepare())
        return MessageIO(SilentPodLogger, link.cmd, link.data)
    }

    private fun establish(messageIO: MessageIO, eapSqn: ByteArray, msgSeq: Byte): SessionKeys =
        assertIs<SessionKeys>(
            SessionEstablisher(
                aapsLogger = SilentPodLogger,
                msgIO = messageIO,
                ltk = ltk,
                eapSqn = eapSqn,
                ids = Ids(uniqueId),
                msgSeq = msgSeq,
                aesEcb = parityAesEcb(),
                randomBytes = CountingRandomBytes()
            ).negotiateSessionKeys()
        )

    /**
     * Android source: `EnDecryptTest.decrypt`. The pod answered with outer sequence 0x0c and
     * ack number 0x16 (22), so the Android outer counter (`SessionKeys.msgSequenceNumber`, a Byte)
     * is not limited to 4 bits. dashctl used to keep one 4 bit counter for everything and mask it
     * with 0x0f. Here a session set up from outer counter 19 (challenge 20, SUCCESS 21) sends its
     * first command with 22 on the wire.
     */
    @Test
    fun outerSequenceCounterIsNotMaskedToFourBits() {
        val messageIO = pairedPod()
        val keys = establish(messageIO, eapSqn = parityHex("000000000002"), msgSeq = 19)
        val session = Session(SilentPodLogger, messageIO, Ids(uniqueId), keys, EnDecrypt(SilentPodLogger, keys.nonce, keys.ck, parityAesCcm()))

        session.sendCommand(
            GetStatusCommand.Builder()
                .setUniqueId(uniqueId.toInt())
                .setSequenceNumber(0)
                .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
                .build()
        )

        assertEquals(22, pod!!.fromPhone.last().sequenceNumber.toInt())
    }

    /**
     * Android source: `SessionEstablisher` keeps its own local `msgSeq`. The command sequence in
     * the command header (`OmnipodDashPodStateManagerImpl.messageSequenceNumber`) is not touched
     * by session set up. The old `DashSessionEstablisher` moved the one shared counter twice.
     */
    @Test
    fun sessionSetupLeavesInnerCommandCounterAlone() {
        val manager = DashPodStateManager(MemoryStore())
        manager.updateFromPairing(uniqueId = uniqueId, ltk = ltk)
        val counterBefore = manager.messageSequenceNumber
        val messageIO = pairedPod()

        establish(messageIO, eapSqn = manager.increaseEapAkaSequenceNumber(), msgSeq = 1)
        manager.commitEapAkaSequenceNumber()

        assertEquals(counterBefore, manager.messageSequenceNumber)
    }
}
