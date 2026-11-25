package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The "Silence" button and the status checker change the pod state from other threads than the
 * queue. Each change copies the whole state, so without one lock a change made from an old copy
 * undid another one made at the same time.
 */
class DashPodStateLockTest {

    private class MemoryStore : DashPodStateStore {
        @Volatile var saved: DashPodState? = null
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saved = state
        }

        override fun clear() {
            saved = null
        }
    }

    @Test
    fun changesFromTwoThreadsAreAllKept() {
        val store = MemoryStore()
        val manager = DashPodStateManager(store)
        val rounds = 2_000
        val start = CountDownLatch(1)

        val queue = thread {
            start.await()
            repeat(rounds) { manager.incrementSuccessfulConnectionAttemptsAfterRetries() }
        }
        val ui = thread {
            start.await()
            repeat(rounds) {
                manager.addAcknowledgedAlert(AlertType.LOW_RESERVOIR)
                manager.forgetAcknowledgedAlert(AlertType.LOW_RESERVOIR)
                manager.incrementFailedConnectionsAfterRetries()
            }
        }
        start.countDown()
        queue.join()
        ui.join()

        assertThat(manager.successfulConnectionAttemptsAfterRetries).isEqualTo(rounds)
        assertThat(manager.failedConnectionsAfterRetries).isEqualTo(rounds)
        assertThat(store.saved?.successfulConnectionAttemptsAfterRetries).isEqualTo(rounds)
    }
}
