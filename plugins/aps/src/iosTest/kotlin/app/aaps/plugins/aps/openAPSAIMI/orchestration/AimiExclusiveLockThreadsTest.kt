package app.aaps.plugins.aps.openAPSAIMI.orchestration

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [AimiExclusiveLock] between two threads, as the loop uses it: one tick takes and releases the lock on
 * its thread, and the next tick, on another thread, must then get it.
 *
 * The single thread tests in `commonTest` cannot see a lock that is never released: the lock is
 * reentrant, so the thread that still holds it takes it again at once.
 */
@OptIn(ObsoleteWorkersApi::class)
class AimiExclusiveLockThreadsTest {

    private val first = Worker.start(name = "tick-1")
    private val second = Worker.start(name = "tick-2")

    @AfterTest
    fun stop() {
        first.requestTermination().result
        second.requestTermination().result
    }

    @Test
    fun aLockReleasedByOneThreadCanBeTakenByAnother() {
        val lock = AimiExclusiveLock()

        val took = first.execute(TransferMode.SAFE, { lock }) {
            val taken = it.tryLock(0L)
            it.unlockIfHeld()
            taken
        }.result
        assertTrue(took, "the first thread must get a free lock")

        val tookAfter = second.execute(TransferMode.SAFE, { lock }) { it.tryLock(500L).also { ok -> if (ok) it.unlockIfHeld() } }.result
        assertTrue(tookAfter, "after the first thread released it, the second thread must get the lock")
    }

    @Test
    fun aLockReleasedTwiceInARowByTwoTicksStaysFree() {
        val lock = AimiExclusiveLock()
        repeat(3) { round ->
            val worker = if (round % 2 == 0) first else second
            val ok = worker.execute(TransferMode.SAFE, { lock }) { it.tryLock(500L).also { taken -> if (taken) it.unlockIfHeld() } }.result
            assertTrue(ok, "tick $round must get the lock")
        }
    }
}
