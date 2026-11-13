package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.BolusCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Pump level parity: the real `IosDashPump` is driven through a fresh pairing, a session set up
 * and one 5 U bolus, against a simulated pod at the level of BLE events (`FakeDashPod` behind the
 * real `DashBleLink` and `MessageIO`). The bytes the pod receives are decrypted and compared with
 * what the Android driver sends in the same situation.
 *
 * Each test pins one place where dashctl used to differ from Android. Do not change an expected
 * value to make a test pass. The expected values come from the Android tests named in
 * [AndroidRecordedVectors] and from the Android code named in each KDoc.
 *
 * iosTest only: `IosDashPump` is in iosMain, so no JVM test can reach it.
 */
class IosDashPumpAndroidParityTest {

    /** What the pod sees after one bolus. */
    private class BolusOnTheWire(
        val header: ByteArray,
        val plaintext: ByteArray,
        val stateManager: DashPodStateManager,
        /** A second bolus asked for right after, while the first is not confirmed. */
        val secondBolusSuccess: Boolean,
        val messagesSentBySecondBolus: Int,
    )

    private var fixture: DashPumpOnFakePod? = null

    @AfterTest
    fun closePod() {
        fixture?.pod?.close()
        fixture?.pod?.assertNoProblems()
    }

    /**
     * Fresh pairing, session set up and one 5 U bolus. Returns the decrypted wire bytes.
     *
     * The pod takes the bolus, but the link is lost before its answer (phone message 7 is the
     * bolus: 1-4 pairing, 5-6 session). So no status says what happened and the bolus stays open.
     *
     * The pulse rate (from the SetUniqueId answer) is given before pairing: that answer also
     * carries the unique id 0x02420001 and would overwrite what pairing stored. This way the
     * stored id, the session and the bolus all use the id pairing stored, and the pins on the id
     * check pairing itself.
     *
     * [podAddress] null: the default `pair()`, which gives the pod the Android id.
     */
    private fun pairEstablishAndBolus(podAddress: ByteArray? = parityHex("02420001")): BolusOnTheWire {
        val f = DashPumpOnFakePod().also { fixture = it }
        f.pairAndConnect(podAddress = podAddress, pulseRateBeforePairing = true)
        f.pod.dropLinkWhileAnswering = 7 to 0

        var secondSuccess = true
        var secondMessages = -1
        runTest {
            val before = f.pod.fromPhone.size
            val result = f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 5.0 })
            // No answer from the pod: the bolus is not reported as delivered.
            assertFalse(result.success, "fixture: a bolus with no answer is not reported as delivered")
            assertEquals(before + 1, f.pod.fromPhone.size, "fixture: exactly one message")
            val beforeSecond = f.pod.fromPhone.size
            secondSuccess = f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 5.0 }).success
            secondMessages = f.pod.fromPhone.size - beforeSecond
        }

        val sent = f.pod.fromPhone.last().bytes
        return BolusOnTheWire(
            header = sent.copyOfRange(0, 16),
            plaintext = f.pod.envelopes.last(),
            stateManager = f.stateManager,
            secondBolusSuccess = secondSuccess,
            messagesSentBySecondBolus = secondMessages
        )
    }

    /**
     * The bolus went out but no answer was read: it stays open, and a retried bolus sends nothing.
     * Android refuses it the same way (`observeNoActiveCommand`). Without this a retry could deliver
     * twice.
     */
    @Test
    fun aSecondBolusIsRefusedWhileTheFirstIsNotConfirmed() {
        val wire = pairEstablishAndBolus()

        val open = wire.stateManager.activeCommand
        assertEquals(DashCommandKind.BOLUS, open?.kind)
        assertEquals(5.0, open?.requestedBolusUnits)
        assertTrue(open?.sent == true, "the first bolus is marked sent before its write")
        assertFalse(wire.secondBolusSuccess, "the second bolus must be refused")
        assertEquals(0, wire.messagesSentBySecondBolus, "the second bolus must send nothing")
    }

    /**
     * Android source: `Session.getCmdMessage` and `EnDecryptTest.encrypt` (plaintext starts with
     * `53302e303d` = `S0.0=` and ends with `2c47302e30` = `,G0.0`).
     * Divergence 1: dashctl sends the bare command.
     */
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
    @Test
    fun commandHeaderDestinationIsThePodId() {
        val header = pairEstablishAndBolus().header

        assertEquals("02420001", header.copyOfRange(12, 16).toParityHex())
    }

    /**
     * Android source: `OmnipodDashBleManagerImpl.pairNewPod` pairs with `Ids.podId`, which is the
     * controller id plus one (0x00001091) for a pod with no unique id yet. `LTKExchanger` sends it in
     * SP1, `updateFromPairing(ids.podId, ...)` stores it, and every packet of the session is
     * addressed to it (`SharedBleManager.establishSession` builds `Ids(podState)`).
     */
    @Test
    fun androidDefaultPairingIdIsSentStoredAndAddressed() {
        val wire = pairEstablishAndBolus(podAddress = null)

        assertEquals(Id(parityHex("00001091")), fixture?.pod?.podId, "the pod id in SP1")
        assertEquals(0x1091L, wire.stateManager.uniqueId)
        assertEquals("00001091", wire.header.copyOfRange(12, 16).toParityHex())
    }

    /**
     * Android source: `OmnipodDashBleManagerImpl` seeds the session with `pairResult.msgSeq` (4 for a
     * fresh pairing). This test reads the stored counter right after pairing, before the session set up.
     * Divergence 7: missing seed call.
     *
     * Still pinned, and it should stay so: the premise is wrong. On Android the pair result seeds the
     * OUTER packet counter of the session (`SessionKeys.msgSequenceNumber`), which is not stored. The
     * stored counter (`OmnipodDashPodStateManagerImpl.messageSequenceNumber`, here
     * `DashPodStateManager.messageSequenceNumber`) is the 4-bit command sequence, and Android does not
     * touch it during pairing (`updateFromPairing`): it stays 0. dashctl now does the same, so this
     * reads 0. That the seed is used is checked on the wire by
     * [firstCommandAfterFreshPairCarriesOuterSequenceSeven].
     */
    @Ignore // PIN, WRONG PREMISE: Android seeds the session (outer) counter from the pair result, not the stored 4-bit command counter, which stays 0. See the KDoc.
    @Test
    fun pairingSeedsTheSequenceCounterFromThePairResult() {
        val f = DashPumpOnFakePod().also { fixture = it }

        f.pump.pair(parityHex("02420001"))

        assertEquals(4, f.stateManager.messageSequenceNumber)
    }
}
