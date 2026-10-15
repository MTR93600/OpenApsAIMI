package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.CapturedCommandPacket
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.DecryptResponse
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.EncryptCommand
import app.aaps.pump.omnipod.dashctl.session.DashEapCodec
import app.aaps.pump.omnipod.dashctl.session.DashMessageTransport
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.pump.omnipod.dashctl.session.DashSessionEstablisher
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Message envelope and counter parity.
 *
 * Part 1: vectors with no `dashctl` counterpart. `dashctl` has no `MessagePacket`, so these
 * tests are ignored. They are the specification for the code that has to be written: remove
 * `@Ignore` and replace the `fail(...)` with the real call when the class exists.
 *
 * Part 2: divergences that can be reached from commonMain. These FAIL today on purpose.
 * Do not change the expected values. The pump level divergences (wrapper, flags, nonce, pulse
 * rate, unique id, seed) live in `IosDashPumpAndroidParityTest` in iosTest, because the code
 * is in iosMain.
 */
class AndroidEnvelopeParityTest {

    // -- Part 1: ignored, no implementation to feed ---------------------------------------------

    /** Android source: `MessagePacketTest.testParseMessagePacket`. */
    @Ignore // IGNORED: dashctl has no MessagePacket. Only a private header() in iosMain IosDashPump.
    @Test
    fun envelopeParsesTheAndroidRealCapture() {
        fail(
            "No MessagePacket.parse in dashctl. Expected from ${CapturedCommandPacket.WIRE_HEX}: " +
                "type ENCRYPTED, source ${CapturedCommandPacket.SOURCE}, destination ${CapturedCommandPacket.DESTINATION}, " +
                "sequence ${CapturedCommandPacket.SEQUENCE_NUMBER}, ack ${CapturedCommandPacket.ACK_NUMBER}, " +
                "eqos ${CapturedCommandPacket.EQOS}, sas ${CapturedCommandPacket.SAS}, tfs ${CapturedCommandPacket.TFS}, " +
                "payload ${CapturedCommandPacket.PAYLOAD_HEX}"
        )
    }

    /** Android source: `MessagePacketTest.testSerializeMessagePacket`. */
    @Ignore // IGNORED: dashctl has no MessagePacket.asByteArray.
    @Test
    fun envelopeSerialisesToTheAndroidRealCapture() {
        fail(
            "No MessagePacket.asByteArray in dashctl. Expected header ${CapturedCommandPacket.HEADER_HEX} " +
                "(flags 11 01, sequence 07, size field 0340 = 26 bytes, the 8 byte MAC is not counted)"
        )
    }

    /** Android source: `EnDecryptTest.encrypt`. The header it signs as associated data. */
    @Ignore // IGNORED: dashctl has no MessagePacket.asByteArray(forEncryption = true).
    @Test
    fun commandEnvelopeHeaderForEncryptionMatchesAndroid() {
        fail(
            "No MessagePacket in dashctl. Expected CCM header ${EncryptCommand.HEADER_HEX} " +
                "for a 26 byte wrapped command (size field counts the plaintext when forEncryption)"
        )
    }

    /** Android source: `EnDecryptTest.decrypt`. A pod answer header: ack bit, last message bit. */
    @Ignore // IGNORED: dashctl has no MessagePacket.parse, so response headers cannot be read.
    @Test
    fun podResponseEnvelopeParsesLikeAndroid() {
        fail(
            "No MessagePacket.parse in dashctl. Expected from header ${DecryptResponse.HEADER_HEX}: " +
                "ack true, lastMessage true, sequence 12, ackNumber 22, size 24 (0x0300 = 24 << 5 >> 5)"
        )
    }

    // -- Part 2: reachable divergences, failing on purpose --------------------------------------

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

    /**
     * Android source: `EnDecryptTest.decrypt`. The pod answered with outer sequence 0x0c and
     * ack number 0x16 (22), so the Android outer counter (`SessionKeys.msgSequenceNumber`, a Byte)
     * is not limited to 4 bits. `dashctl` keeps one 4 bit counter for everything and masks with 0x0f.
     * Divergence: counter width (item C in the plan).
     */
    @Test
    fun outerSequenceCounterIsNotMaskedToFourBits() {
        val manager = DashPodStateManager(MemoryStore())

        manager.seedMessageSequenceNumber(22)

        assertEquals(22, manager.messageSequenceNumber)
    }

    /**
     * Android source: `SessionEstablisher` keeps its own local `msgSeq`. The command sequence in
     * the command header (`OmnipodDashPodStateManagerImpl.messageSequenceNumber`) is not touched
     * by session set up. `DashSessionEstablisher` moves the one shared counter twice.
     * Divergence: inner counter disturbed by session set up (item D in the plan).
     */
    @Test
    fun sessionSetupLeavesInnerCommandCounterAlone() {
        val ltk = parityHex("00112233445566778899aabbccddeeff")
        val rand = parityHex("23553cbe9637a89d218ae64dae47bf35")
        val nodeIv = parityHex("b0b1b2b3")
        val ecb = parityAesEcb()
        val manager = DashPodStateManager(MemoryStore())
        manager.updateFromPairing(uniqueId = 0x02420001L, ltk = ltk)
        val counterBefore = manager.messageSequenceNumber

        val pod = object : DashMessageTransport {
            private var lastChallenge: ByteArray? = null
            override fun send(data: ByteArray): Boolean {
                if (data.size > 4) lastChallenge = data
                return true
            }

            override fun receive(): ByteArray {
                val challenge = DashEapCodec.parseChallenge(lastChallenge!!)
                // The EAP SQN is 2 for this first session: updateFromPairing sets 1, then it is increased.
                val milenage = DashMilenage(ecb, ltk, DashPodStateManager.eapSqnBytes(2), randParam = challenge.rand)
                return DashEapCodec.response(challenge.identifier, milenage.res, nodeIv)
            }
        }
        DashSessionEstablisher(
            transport = pod,
            stateManager = manager,
            aesEcb = ecb,
            controllerIv = parityHex("a0a1a2a3"),
            rand = rand,
            identifier = 0x42
        ).negotiateSessionKeys()

        assertEquals(counterBefore, manager.messageSequenceNumber)
    }
}
