package app.aaps.pump.omnipod.dashctl.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A write the store refuses (the iOS Keychain can) must stop the next delivery command. Without the
 * open command and its "sent" mark on disk, an app killed after the write would forget a command the
 * pod may have taken, and could send the bolus again.
 *
 * Test names here carry no comma: Kotlin/Native rejects one inside a backtick quoted name.
 */
class DashPodStateSaveFailureTest {

    /** Stores in memory; refuses every write while [failing] is true. */
    private class FlakyStore : DashPodStateStore {
        var saved: DashPodState? = null
        var failing = false
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saveChecked(state)
        }

        override fun saveChecked(state: DashPodState): Boolean {
            if (failing) return false
            saved = state
            return true
        }

        override fun clear() {
            saved = null
        }
    }

    private fun openBolus(manager: DashPodStateManager) =
        manager.createActiveCommand(kind = DashCommandKind.BOLUS, historyId = 1, createdAtMs = 1_000L, requestedBolusUnits = 1.0)

    @Test
    fun `an open command that cannot be saved is refused and not kept`() {
        val store = FlakyStore()
        val manager = DashPodStateManager(store)
        store.failing = true

        assertFailsWith<DashPodStateNotSavedException> { openBolus(manager) }

        assertNull(manager.activeCommand)
    }

    @Test
    fun `a sent mark that cannot be saved is refused and the command stays not sent`() {
        val store = FlakyStore()
        val manager = DashPodStateManager(store)
        openBolus(manager)
        store.failing = true

        assertFailsWith<DashPodStateNotSavedException> { manager.markActiveCommandSent() }

        assertFalse(assertNotNull(manager.activeCommand).sent)
    }

    @Test
    fun `an earlier failed write blocks the next command until a new try works`() {
        val store = FlakyStore()
        val manager = DashPodStateManager(store)
        store.failing = true
        manager.increaseMessageSequenceNumber()

        // The pod saw the new sequence: memory keeps it even though the store did not.
        assertEquals(1, manager.messageSequenceNumber)
        assertFailsWith<DashPodStateNotSavedException> { openBolus(manager) }

        store.failing = false
        openBolus(manager)

        assertEquals(1, assertNotNull(store.saved).messageSequenceNumber)
        assertNotNull(assertNotNull(store.saved).activeCommand)
    }

    @Test
    fun `a store that cannot fail changes nothing`() {
        val store = FlakyStore()
        val manager = DashPodStateManager(store)

        openBolus(manager)
        manager.markActiveCommandSent()

        assertEquals(true, assertNotNull(store.saved).activeCommand?.sent)
    }
}
