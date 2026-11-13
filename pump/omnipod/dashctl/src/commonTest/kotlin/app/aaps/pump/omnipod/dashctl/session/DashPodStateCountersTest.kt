package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The parts of [DashPodStateManager] the pump needs for the Android counters and the bolus: the
 * pulse rate from SetUniqueId, the EAP SQN after a resynchronization, and the 4-bit command
 * sequence that pairing leaves alone. Android source: `OmnipodDashPodStateManagerImpl`.
 */
class DashPodStateCountersTest {

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

    /** Android `SetUniqueIdResponseTest`: pump rate 16, unique id 37879809. */
    private val setUniqueIdResponse = SetUniqueIdResponse(parityHex("011B13881008340A50040A00010300040308146CC1000954D402420001"))

    @Test
    fun `the pulse rate is not known before SetUniqueId`() {
        assertNull(DashPodStateManager(MemoryStore()).pulseRate)
    }

    @Test
    fun `SetUniqueId stores the pulse rate and the unique id of the pod`() {
        val store = MemoryStore()
        val manager = DashPodStateManager(store)

        manager.updateFromSetUniqueIdResponse(setUniqueIdResponse, 0L)

        assertEquals(16.toShort(), manager.pulseRate)
        assertEquals(37879809L, manager.uniqueId)
        assertEquals(16.toShort(), store.saved?.pulseRate, "stored at once")
    }

    @Test
    fun `the pulse rate survives a restart`() {
        val state = DashPodState(ltk = ByteArray(16), uniqueId = 37879809L, messageSequenceNumber = 3, pulseRate = 16)

        val back = DashPodStateCodec.decode(DashPodStateCodec.encode(state))

        assertEquals(state, back)
        assertEquals(16.toShort(), back.pulseRate)
    }

    @Test
    fun `a version 1 state has no pulse rate`() {
        val v1 = byteArrayOf(1, 0) +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + // eapAkaSequenceNumber 1
            byteArrayOf(0, 0, 0, 0) + // messageSequenceNumber 0
            byteArrayOf(0, 0, 0, 0) // activationProgress ordinal 0

        assertNull(DashPodStateCodec.decode(v1).pulseRate)
    }

    /**
     * A state stored by the phase 1 build: version 2, which ends after the active command and has
     * no pulse rate. It must still be read, with its key and its open bolus. The pulse rate came
     * later, in version 3.
     */
    @Test
    fun `a version 2 state without a pulse rate is still read`() {
        val ltk = ByteArray(16) { (it + 1).toByte() }
        val v2 = byteArrayOf(2, 0x03) + ltk +
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5) + // eapAkaSequenceNumber 5
            byteArrayOf(0, 0, 0, 7) + // messageSequenceNumber 7
            byteArrayOf(0, 0, 0, 0, 0x02, 0x42, 0x00, 0x01) + // uniqueId
            byteArrayOf(0, 0, 0, 0) + // activationProgress ordinal 0
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 4) + // statusResponseCount 4
            byteArrayOf(0) + // no pod status
            byteArrayOf(1, 7, 0) + // active command: sequence 7, kind BOLUS
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) + // historyId 0
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 4) + // statusCountAtCreation 4
            byteArrayOf(1, 0x01) + // sent, bolus present
            byteArrayOf(0x40, 0x14, 0, 0, 0, 0, 0, 0) // 5.0 U

        val state = DashPodStateCodec.decode(v2)

        assertEquals(ltk.toParityHex(), state.ltk?.toParityHex())
        assertEquals(0x02420001L, state.uniqueId)
        assertEquals(7, state.messageSequenceNumber)
        assertEquals(5.0, state.activeCommand?.requestedBolusUnits)
        assertEquals(true, state.activeCommand?.sent)
        assertNull(state.pulseRate)
    }

    @Test
    fun `pairing leaves the command sequence alone`() {
        val manager = DashPodStateManager(MemoryStore())
        repeat(5) { manager.increaseMessageSequenceNumber() }

        manager.updateFromPairing(uniqueId = 0x02420001L, ltk = ByteArray(16))

        // Android updateFromPairing sets only the EAP SQN, the LTK and the unique id.
        assertEquals(5, manager.messageSequenceNumber)
        assertEquals(1L, manager.eapAkaSequenceNumber)
    }

    @Test
    fun `the command sequence wraps at sixteen`() {
        val manager = DashPodStateManager(MemoryStore())
        repeat(17) { manager.increaseMessageSequenceNumber() }
        assertEquals(1, manager.messageSequenceNumber)
    }

    @Test
    fun `after a resynchronization the next SQN is one above the SQN of the pod`() {
        val store = MemoryStore()
        val manager = DashPodStateManager(store)
        manager.updateFromPairing(uniqueId = 0x02420001L, ltk = ByteArray(16))

        manager.updateEapAkaSequenceNumber(0x15e)

        assertEquals(0x15eL, store.saved?.eapAkaSequenceNumber, "stored at once, as the Android setter")
        assertEquals("00000000015f", manager.increaseEapAkaSequenceNumber().toParityHex())
        assertEquals(0x15eL, store.saved?.eapAkaSequenceNumber, "an increase is stored only by commit")
        manager.commitEapAkaSequenceNumber()
        assertEquals(0x15fL, store.saved?.eapAkaSequenceNumber)
    }
}
