package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DashPodStateManager] when the store cannot read what it holds (the Keychain before the first
 * unlock after a reboot, or bytes that do not decode). The state is "unknown", not "no pod": nothing
 * may be written over the stored item, which may hold the key of a pod on the body.
 */
class DashPodStateUnknownTest {

    /** A store that holds the state of a running pod, and can be made unreadable. */
    private class LockableStore(var stored: DashPodState?) : DashPodStateStore {

        var unreadable: String? = null
        val saves = mutableListOf<DashPodState>()
        var clears = 0

        override fun load(): DashPodState? = stored

        override fun read(): DashPodStateRead =
            unreadable?.let { DashPodStateRead.Unreadable(it) }
                ?: stored?.let { DashPodStateRead.Loaded(it) }
                ?: DashPodStateRead.Empty

        override fun save(state: DashPodState) {
            saves += state
            stored = state
        }

        override fun clear() {
            clears++
            stored = null
        }
    }

    private val runningPod = DashPodState(
        ltk = ByteArray(DashPodState.LTK_SIZE) { 7 },
        uniqueId = 0x02420001L,
        activationProgress = ActivationProgress.COMPLETED,
        bluetoothAddress = "peripheral-1",
    )

    @Test
    fun `an unreadable store gives an unknown state and not an empty known one`() {
        val store = LockableStore(runningPod).apply { unreadable = "Keychain status -25308" }

        val manager = DashPodStateManager(store)

        assertFalse(manager.isStateKnown)
        assertEquals("Keychain status -25308", manager.stateUnreadableReason)
        assertNull(manager.ltk, "the state is not read, so there is no key to use")
    }

    @Test
    fun `nothing stored is a known state with no pod`() {
        val manager = DashPodStateManager(LockableStore(null))

        assertTrue(manager.isStateKnown)
        assertNull(manager.stateUnreadableReason)
        assertEquals(ActivationProgress.NOT_STARTED, manager.activationProgress)
    }

    @Test
    fun `no write reaches the store while the state is unknown`() {
        val store = LockableStore(runningPod).apply { unreadable = "locked" }
        val manager = DashPodStateManager(store)

        assertFailsWith<DashPodStateUnknownException> { manager.updateBluetoothAddress("peripheral-2") }
        assertFailsWith<DashPodStateUnknownException> { manager.updateFromPairing(uniqueId = 1L, ltk = ByteArray(DashPodState.LTK_SIZE)) }
        assertFailsWith<DashPodStateUnknownException> { manager.reset() }
        assertFailsWith<DashPodStateUnknownException> { manager.updateActivationProgress(ActivationProgress.GOT_POD_VERSION) }

        assertTrue(store.saves.isEmpty(), "the stored item is never replaced")
        assertEquals(0, store.clears)
        assertTrue(store.stored!!.ltk!!.contentEquals(runningPod.ltk!!), "the key of the pod on the body is kept")
    }

    @Test
    fun `a later read that works gives the stored pod back`() {
        val store = LockableStore(runningPod).apply { unreadable = "locked" }
        val manager = DashPodStateManager(store)
        assertFalse(manager.reloadIfUnknown(), "still locked")
        assertFalse(manager.isStateKnown)

        store.unreadable = null
        assertTrue(manager.reloadIfUnknown())

        assertTrue(manager.isStateKnown)
        assertEquals(0x02420001L, manager.uniqueId)
        assertEquals("peripheral-1", manager.bluetoothAddress)
        assertTrue(manager.ltk!!.contentEquals(runningPod.ltk!!))
        manager.updateActivationProgress(ActivationProgress.COMPLETED)
        assertEquals(1, store.saves.size, "writes work again once the state is known")
    }

    @Test
    fun `a later read that finds nothing stored gives a known empty state`() {
        val store = LockableStore(null).apply { unreadable = "locked" }
        val manager = DashPodStateManager(store)

        store.unreadable = null
        assertTrue(manager.reloadIfUnknown())
        assertNull(manager.ltk)
        assertEquals(ActivationProgress.NOT_STARTED, manager.activationProgress)
    }

    @Test
    fun `a known state is not read again`() {
        val store = LockableStore(runningPod)
        val manager = DashPodStateManager(store)
        manager.updateActivationProgress(ActivationProgress.COMPLETED)
        store.stored = null

        assertTrue(manager.reloadIfUnknown())
        assertEquals(0x02420001L, manager.uniqueId, "the state in memory stays")
    }
}
