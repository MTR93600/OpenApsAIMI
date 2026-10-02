package app.aaps.plugins.aps.openAPSAIMI.orchestration

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [AimiExclusiveLock] on every target. The loop gate needs three things from it: a free lock is
 * taken, the thread holding it may take it again, and a release without a matching take does nothing.
 *
 * Blocking between threads is not tested here because `commonTest` has no portable way to start a
 * second thread. What is tested is what the gate relies on inside one tick.
 */
class AimiExclusiveLockTest {

    @Test
    fun aFreeLockIsTakenAndCanBeTakenAgainAfterRelease() {
        val lock = AimiExclusiveLock()
        assertTrue(lock.tryLock(0L), "a fresh lock must be free")
        lock.unlockIfHeld()
        assertTrue(lock.tryLock(0L), "the lock must be free again after release")
        lock.unlockIfHeld()
    }

    @Test
    fun theHolderCanTakeTheLockAgainAndMustReleaseEveryLevel() {
        val lock = AimiExclusiveLock()
        assertTrue(lock.tryLock(0L))
        assertTrue(lock.tryLock(0L), "the lock is reentrant for the thread that holds it")
        lock.unlockIfHeld()
        lock.unlockIfHeld()
        assertTrue(lock.tryLock(0L), "both levels must be gone after two releases")
        lock.unlockIfHeld()
    }

    @Test
    fun releasingALockNobodyHoldsDoesNothing() {
        val lock = AimiExclusiveLock()
        lock.unlockIfHeld()
        lock.unlockIfHeld()
        assertTrue(lock.tryLock(0L), "the lock must still be usable")
        lock.unlockIfHeld()
        assertTrue(lock.tryLock(0L), "and the stray releases must not have left a level behind")
        lock.unlockIfHeld()
    }

    @Test
    fun aNegativeWaitIsReadAsNoWait() {
        val lock = AimiExclusiveLock()
        assertTrue(lock.tryLock(-5L), "a free lock is still taken with no wait at all")
        lock.unlockIfHeld()
    }
}
