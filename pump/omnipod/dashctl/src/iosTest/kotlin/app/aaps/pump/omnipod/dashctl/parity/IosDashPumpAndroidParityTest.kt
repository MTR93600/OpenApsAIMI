package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCcmCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.BolusCommand
import app.aaps.pump.omnipod.dashctl.session.DashEapCodec
import app.aaps.pump.omnipod.dashctl.session.DashKeyValueCodec
import app.aaps.pump.omnipod.dashctl.session.DashMessageTransport
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Pump level parity: the real `IosDashPump` is driven through a fresh pairing, a session set up
 * and one 5 U bolus, against a fake pod. The bytes that reach the transport are decrypted and
 * compared with what the Android driver sends in the same situation.
 *
 * These tests are expected to FAIL today. Each one pins one divergence. Do not change an
 * expected value to make a test pass. The expected values come from the Android tests named in
 * [AndroidRecordedVectors] and from the Android code named in each KDoc.
 *
 * iosTest only: `IosDashPump` is in iosMain, so no JVM test can reach it.
 */
class IosDashPumpAndroidParityTest {

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

    /** Plays the pod: answers the pairing messages from a script, then the EAP-AKA challenge. */
    private class FakePod(private val scripted: ArrayDeque<ByteArray>, private val ltkProvider: () -> ByteArray) :
        DashMessageTransport {

        val sent = mutableListOf<ByteArray>()
        var nodeIv = parityHex("b0b1b2b3")
        var podMilenage: DashMilenage? = null
        var controllerIv: ByteArray? = null

        override fun send(data: ByteArray): Boolean {
            sent += data
            return true
        }

        override fun receive(): ByteArray? {
            scripted.removeFirstOrNull()?.let { return it }
            val challenge = DashEapCodec.parseChallenge(sent.last())
            controllerIv = challenge.controllerIv
            // First session after a pairing: updateFromPairing sets the EAP SQN to 1, it is increased to 2.
            val milenage = DashMilenage(
                IosAesEcbBlockCipher(),
                ltkProvider(),
                DashPodStateManager.eapSqnBytes(2),
                randParam = challenge.rand
            )
            podMilenage = milenage
            return DashEapCodec.response(challenge.identifier, milenage.res, nodeIv)
        }
    }

    /** What the pod sees after one bolus. */
    private class BolusOnTheWire(
        val header: ByteArray,
        val plaintext: ByteArray,
        val stateManager: DashPodStateManager
    )

    private val podAddress = parityHex("02420001")

    /** Fresh pairing, session set up and one 5 U bolus. Returns the decrypted wire bytes. */
    private fun pairEstablishAndBolus(): BolusOnTheWire {
        val x25519 = IosX25519Dh()
        val cmac = IosAesCmac()
        val pdmPrivate = parityHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pdmNonce = parityHex("000102030405060708090a0b0c0d0e0f")
        val podPrivate = parityHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val podNonce = parityHex("101112131415161718191a1b1c1d1e1f")

        // The pod side of the key exchange needs the same keys the pump derives. The pump's own
        // derivation is proven against Android in AndroidCryptoParityTest, so it is reused here
        // only to script the pod answers.
        val podSide = DashKeyExchange(x25519, cmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        podSide.updatePodPublicData(x25519.publicFromPrivate(podPrivate) + podNonce)

        val scripted = ArrayDeque<ByteArray>()
        scripted += DashKeyValueCodec.formatKeys(arrayOf("SPS1="), arrayOf(x25519.publicFromPrivate(podPrivate) + podNonce))
        scripted += DashKeyValueCodec.formatKeys(arrayOf("SPS2="), arrayOf(podSide.podConf))
        scripted += DashKeyValueCodec.formatKeys(arrayOf("P0="), arrayOf(byteArrayOf(0xa5.toByte())))

        val pod = FakePod(scripted) { podSide.ltk }
        val stateManager = DashPodStateManager(MemoryStore())
        val pump = IosDashPump(
            transport = pod,
            stateManager = stateManager,
            keyExchange = DashKeyExchange(x25519, cmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        )

        pump.pair(podAddress)
        pump.establishSession()
        pump.connect("parity")
        var wire: ByteArray? = null
        runTest {
            val before = pod.sent.size
            val result = pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 5.0 })
            assertTrue(result.success, "fixture: the bolus must be sent")
            assertEquals(before + 1, pod.sent.size, "fixture: exactly one message")
            wire = pod.sent.last()
        }

        val sent = wire!!
        val header = sent.copyOfRange(0, 16)
        // The Android encrypt direction: nonce sequence 1, pod receiving.
        val nonce = DashNonce(pod.controllerIv!! + pod.nodeIv, 0).increment(podReceiving = true)
        val plaintext = IosAesCcmCipher().decrypt(
            key = pod.podMilenage!!.ck,
            nonce = nonce,
            associatedData = header,
            ciphertextWithMac = sent.copyOfRange(16, sent.size),
            macSizeBytes = 8
        )
        return BolusOnTheWire(header, plaintext, stateManager)
    }

    /**
     * Android source: `Session.getCmdMessage` and `EnDecryptTest.encrypt` (plaintext starts with
     * `53302e303d` = `S0.0=` and ends with `2c47302e30` = `,G0.0`).
     * Divergence 1: dashctl sends the bare command.
     */
    @Ignore // PIN, EXPECTED TO FAIL: dashctl sends the bare command; Android wraps it in the S0.0=/G0.0 key-value envelope.
    @Test
    fun commandPayloadIsWrappedInS00() {
        val plain = pairEstablishAndBolus().plaintext.toParityHex()

        assertTrue(plain.startsWith("53302e303d"), "plaintext must start with S0.0= but is $plain")
        assertTrue(plain.endsWith("2c47302e30"), "plaintext must end with ,G0.0 but is $plain")
    }

    /**
     * Android source: `EnDecryptTest.encrypt` header `54 57 11 01` (flags 0x11, eqos 1) and
     * `MessagePacketTest` header `54 57 11 01`. dashctl sends `54 57 10 01`.
     * Divergence 2: flags byte 0x10 versus 0x11.
     */
    @Ignore // PIN, EXPECTED TO FAIL: dashctl hardcodes flag byte 0x10; Android sends eqos=1, so 0x11.
    @Test
    fun commandHeaderFlagsAreEqosOne() {
        val header = pairEstablishAndBolus().header

        assertEquals("54571101", header.copyOfRange(0, 4).toParityHex())
    }

    /**
     * Android source: `MessagePacketTest` and `LTKExchanger`/`SessionEstablisher`/`Session`.
     * After a fresh pairing the pair result sequence is 4, the session set up adds 2, and
     * `Session.sendCommand` adds 1 before it builds the packet: the first command carries
     * outer sequence 7. `MessagePacketTest` has the same header byte `07` for a first command.
     * Divergence 3 (and 7): dashctl never seeds the counter and reads it before the increment.
     */
    @Ignore // PIN, EXPECTED TO FAIL: dashctl starts the outer sequence at 2; Android reaches 7 after pairing and session setup.
    @Test
    fun firstCommandAfterFreshPairCarriesOuterSequenceSeven() {
        val header = pairEstablishAndBolus().header

        assertEquals(7, header[4].toInt() and 0xff)
    }

    /**
     * Android source: `OmnipodDashManagerImpl.NONCE = 1229869870` (fixed), `ProgramBolusCommandTest`
     * (nonce bytes `494e532e` in the command). dashctl draws a random nonce for each command.
     * Divergence 4: fixed versus random nonce.
     */
    @Ignore // PIN, EXPECTED TO FAIL: dashctl draws 4 random bytes per command; Android uses the fixed nonce 1229869870.
    @Test
    fun commandNonceIsTheFixedAndroidNonce() {
        val plain = pairEstablishAndBolus().plaintext.toParityHex()

        assertTrue(plain.contains(BolusCommand.NONCE_HEX), "command must contain the fixed nonce but is $plain")
    }

    /**
     * Android source: `OmnipodDashManagerImpl.bolus` passes `podStateManager.pulseRate` (16 on a real pod,
     * see `ProgramBolusCommandTest`). With 16 the first pulse delay field is `00030d40`. dashctl hardcodes 2,
     * integer division gives a first pulse delay of `00000000`.
     * Divergence 5: hardcoded pulse rate. A bolus command with a zero delay field.
     */
    @Ignore // PIN, EXPECTED TO FAIL: dashctl hardcodes a pulse rate of 2, so the first-pulse delay is 0; Android passes the pod's own rate. This changes the bolus bytes.
    @Test
    fun bolusFirstPulseDelayUsesPodPulseRate() {
        val plain = pairEstablishAndBolus().plaintext.toParityHex()

        assertTrue(
            plain.contains(BolusCommand.FIRST_PULSE_DELAY_HEX),
            "bolus must contain first pulse delay ${BolusCommand.FIRST_PULSE_DELAY_HEX} but is $plain"
        )
    }

    /**
     * Android source: `LTKExchanger` sends `ids.podId.address` in SP1 and `updateFromPairing(ids.podId, ...)`
     * stores the same id. dashctl `pair(podAddress)` sends it in SP1 and then stores 0.
     * Divergence 6: unique id 0.
     */
    @Ignore // PIN, EXPECTED TO FAIL: dashctl's pair() stores uniqueId 0 and drops the pod address it was given.
    @Test
    fun pairingStoresThePodIdFromTheAddress() {
        val state = pairEstablishAndBolus().stateManager

        assertEquals(0x02420001L, state.uniqueId)
    }

    /**
     * Android source: every Android packet is addressed to `ids.podId` (`EnDecryptTest.encrypt` header
     * bytes 12 to 15 are `02420001`). dashctl writes the stored unique id, which is 0 after pairing.
     * Same cause as divergence 6, seen on the wire.
     */
    @Ignore // PIN, EXPECTED TO FAIL: follows from the uniqueId of 0: the packet destination is 0 instead of the pod id.
    @Test
    fun commandHeaderDestinationIsThePodId() {
        val header = pairEstablishAndBolus().header

        assertEquals("02420001", header.copyOfRange(12, 16).toParityHex())
    }

    /**
     * Android source: `OmnipodDashBleManagerImpl` seeds the session with `pairResult.msgSeq` (4 for a
     * fresh pairing). dashctl `pair()` drops `result.msgSeq` and nothing calls `seedMessageSequenceNumber`.
     * This test reads the counter right after pairing, before the session set up.
     * Divergence 7: missing seed call.
     */
    @Ignore // PIN, EXPECTED TO FAIL: seedMessageSequenceNumber is never called. Do NOT fix as the KDoc suggests: seeding dashctl's single counter would break its inner counter.
    @Test
    fun pairingSeedsTheSequenceCounterFromThePairResult() {
        val x25519 = IosX25519Dh()
        val cmac = IosAesCmac()
        val pdmPrivate = parityHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pdmNonce = parityHex("000102030405060708090a0b0c0d0e0f")
        val podPrivate = parityHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val podNonce = parityHex("101112131415161718191a1b1c1d1e1f")
        val podSide = DashKeyExchange(x25519, cmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        podSide.updatePodPublicData(x25519.publicFromPrivate(podPrivate) + podNonce)
        val scripted = ArrayDeque<ByteArray>()
        scripted += DashKeyValueCodec.formatKeys(arrayOf("SPS1="), arrayOf(x25519.publicFromPrivate(podPrivate) + podNonce))
        scripted += DashKeyValueCodec.formatKeys(arrayOf("SPS2="), arrayOf(podSide.podConf))
        scripted += DashKeyValueCodec.formatKeys(arrayOf("P0="), arrayOf(byteArrayOf(0xa5.toByte())))
        val stateManager = DashPodStateManager(MemoryStore())
        val pump = IosDashPump(
            transport = FakePod(scripted) { podSide.ltk },
            stateManager = stateManager,
            keyExchange = DashKeyExchange(x25519, cmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        )

        pump.pair(podAddress)

        assertEquals(4, stateManager.messageSequenceNumber)
    }
}
