package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlinx.cinterop.ExperimentalForeignApi
import platform.HealthKit.HKErrorAuthorizationDenied
import platform.HealthKit.HKErrorDatabaseInaccessible
import platform.HealthKit.HKErrorDomain
import platform.HealthKit.HKErrorNoData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A locked iPhone must not be read as "no data": that stored an UNKNOWN physio context over the last
 * good one. These are the two rules that keep the run away from it.
 */
@OptIn(ExperimentalForeignApi::class)
class PhysioLockPauseTest {

    private val start = 1_000_000L

    @Test
    fun `nothing waits before a lock`() {
        assertFalse(PhysioLockPause().paused(start))
    }

    @Test
    fun `ticks wait after a lock`() {
        val pause = PhysioLockPause()
        pause.locked(start)
        assertTrue(pause.paused(start))
        assertTrue(pause.paused(start + PhysioLockPause.LOCK_RETRY_MS - 1))
    }

    @Test
    fun `the wait ends by itself when the unlock notice never comes`() {
        val pause = PhysioLockPause()
        pause.locked(start)
        assertFalse(pause.paused(start + PhysioLockPause.LOCK_RETRY_MS))
    }

    @Test
    fun `a clock moved back ends the wait`() {
        val pause = PhysioLockPause()
        pause.locked(start)
        assertFalse(pause.paused(start - 1))
    }

    @Test
    fun `the unlock ends the wait and says a run was waiting`() {
        val pause = PhysioLockPause()
        pause.locked(start)
        assertTrue(pause.unlocked())
        assertFalse(pause.paused(start))
    }

    @Test
    fun `an unlock with nothing waiting starts no run`() {
        val pause = PhysioLockPause()
        assertFalse(pause.unlocked())
        pause.locked(start)
        pause.unlocked()
        assertFalse(pause.unlocked())
    }

    @Test
    fun `only the locked database error counts as locked`() {
        assertTrue(isHealthDataLockedError(HKErrorDomain, HKErrorDatabaseInaccessible))
        assertFalse(isHealthDataLockedError(HKErrorDomain, HKErrorNoData))
        assertFalse(isHealthDataLockedError(HKErrorDomain, HKErrorAuthorizationDenied))
        assertFalse(isHealthDataLockedError("NSCocoaErrorDomain", HKErrorDatabaseInaccessible))
        assertFalse(isHealthDataLockedError(null, HKErrorDatabaseInaccessible))
    }
}
