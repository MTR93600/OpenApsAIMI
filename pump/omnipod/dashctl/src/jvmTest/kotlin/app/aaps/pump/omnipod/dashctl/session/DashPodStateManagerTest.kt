package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for [DashPodStateManager]: persistence round-trip, the 4-bit message
 * sequence wrap, and the EAP-AKA sequence number lifecycle.
 */
class DashPodStateManagerTest {

    private class InMemoryStore : DashPodStateStore {
        var saved: DashPodState? = null
        var saveCount = 0
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saved = state
            saveCount++
        }

        override fun clear() {
            saved = null
        }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun freshManagerStartsFromDefaults() {
        val manager = DashPodStateManager(InMemoryStore())

        assertNull(manager.ltk)
        assertEquals(1L, manager.eapAkaSequenceNumber)
        assertEquals(0, manager.messageSequenceNumber)
        assertNull(manager.uniqueId)
        assertEquals(ActivationProgress.NOT_STARTED, manager.activationProgress)
        assertNull(manager.bluetoothAddress)
    }

    @Test
    fun updateFromPairingPersistsLtkAndResetsEapSqn() {
        val store = InMemoryStore()
        val manager = DashPodStateManager(store)
        val ltk = hex("00112233445566778899aabbccddeeff")

        manager.updateFromPairing(uniqueId = 0x1F01482BL, ltk = ltk)

        assertContentEquals(ltk, manager.ltk)
        assertEquals(0x1F01482BL, manager.uniqueId)
        assertEquals(1L, manager.eapAkaSequenceNumber)
        // Persisted immediately: a fresh manager sees the pairing.
        val reloaded = DashPodStateManager(store)
        assertContentEquals(ltk, reloaded.ltk)
        assertEquals(0x1F01482BL, reloaded.uniqueId)
    }

    @Test
    fun updateFromPairingCopiesLtk() {
        val manager = DashPodStateManager(InMemoryStore())
        val ltk = hex("00112233445566778899aabbccddeeff")

        manager.updateFromPairing(uniqueId = 1L, ltk = ltk)
        ltk[0] = 0x7f

        assertEquals(0x00.toByte(), manager.ltk!![0], "Manager must hold a copy of the LTK")
    }

    @Test
    fun updateFromPairingRejectsBadLtkSize() {
        val manager = DashPodStateManager(InMemoryStore())
        assertFailsWith<IllegalArgumentException> {
            manager.updateFromPairing(uniqueId = 1L, ltk = ByteArray(8))
        }
    }

    @Test
    fun messageSequenceNumberWrapsAt4BitsAndPersists() {
        val store = InMemoryStore()
        val manager = DashPodStateManager(store)

        repeat(15) { manager.increaseMessageSequenceNumber() }
        assertEquals(15, manager.messageSequenceNumber)

        manager.increaseMessageSequenceNumber()
        assertEquals(0, manager.messageSequenceNumber, "4-bit counter must wrap 15 -> 0")
        assertEquals(0, store.saved!!.messageSequenceNumber)
        assertEquals(0, DashPodStateManager(store).messageSequenceNumber)
    }

    @Test
    fun eapAkaSequenceNumberIncrementsWithoutPersistingUntilCommit() {
        val store = InMemoryStore()
        val manager = DashPodStateManager(store)
        manager.updateFromPairing(uniqueId = 1L, ltk = ByteArray(16))

        val sqnBytes = manager.increaseEapAkaSequenceNumber()

        assertEquals(2L, manager.eapAkaSequenceNumber)
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 2), sqnBytes, "EAP SQN is 6 big-endian bytes")
        assertEquals(1L, store.saved!!.eapAkaSequenceNumber, "Must not persist before commit")

        manager.commitEapAkaSequenceNumber()
        assertEquals(2L, store.saved!!.eapAkaSequenceNumber)
        assertEquals(2L, DashPodStateManager(store).eapAkaSequenceNumber)
    }

    @Test
    fun resetClearsEverything() {
        val store = InMemoryStore()
        val manager = DashPodStateManager(store)
        manager.updateFromPairing(uniqueId = 1L, ltk = ByteArray(16))
        manager.increaseMessageSequenceNumber()

        manager.reset()

        assertNull(manager.ltk)
        assertNull(manager.uniqueId)
        assertEquals(1L, manager.eapAkaSequenceNumber)
        assertEquals(0, manager.messageSequenceNumber)
        assertEquals(DashPodState(), store.saved)
    }

    @Test
    fun stateRejectsOutOfRangeMessageSequence() {
        assertFailsWith<IllegalArgumentException> { DashPodState(messageSequenceNumber = 16) }
        assertFailsWith<IllegalArgumentException> { DashPodState(messageSequenceNumber = -1) }
    }

    @Test
    fun stateEqualsComparesLtkByContent() {
        val ltk = ByteArray(16) { it.toByte() }
        assertEquals(DashPodState(ltk = ltk), DashPodState(ltk = ltk.copyOf()))
    }
}
