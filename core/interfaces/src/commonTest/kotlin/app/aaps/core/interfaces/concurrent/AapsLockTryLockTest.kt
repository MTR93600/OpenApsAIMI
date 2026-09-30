package app.aaps.core.interfaces.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The non-blocking half of [AapsLock], on every target.
 *
 * `AapsLockTest` is an `androidHostTest` and can only speak for the JVM `ReentrantLock`. The iOS
 * `actual` is an `NSRecursiveLock`, a different library with its own idea of what `tryLock` means,
 * and the whole point of the method is a property that is invisible when it works and dangerous when
 * it does not: **it must not wait**. The first caller is the Autodrive data lake, which runs on the
 * APS decision thread on every tick and shares its file with a backfiller that can hold the lock
 * across the whole training corpus. A `tryLock` that quietly blocked there would turn "skip this
 * training row" into "delay this insulin decision".
 *
 * So these are written in `commonTest` on purpose: the same assertions run against `ReentrantLock`
 * and against `NSRecursiveLock`, and a platform that disagrees fails here rather than on a phone.
 */
@OptIn(ExperimentalAtomicApi::class)
class AapsLockTryLockTest {

    @Test
    fun `a free lock is taken`() {
        val lock = AapsLock()

        assertTrue(lock.tryLock())
        lock.unlock()
    }

    /**
     * Reentrancy, which `ReentrantLock.tryLock()` and `NSRecursiveLock.tryLock()` both promise and a
     * plain `NSLock` would not: a thread that already holds the lock must not fail against itself.
     * Every level taken is released below, so the lock is free again afterwards.
     */
    @Test
    fun `a lock held by the same thread is re-entered`() {
        val lock = AapsLock()

        lock.lock()
        assertTrue(lock.tryLock(), "a recursive lock must let its own holder in")
        assertTrue(lock.tryLock(), "and again, one level deeper")
        lock.unlock()
        lock.unlock()
        lock.unlock()

        // All three levels given back, so a fresh attempt still succeeds.
        assertTrue(lock.tryLock())
        lock.unlock()
    }

    @Test
    fun `tryWithLock runs the block and returns its value when the lock is free`() {
        val lock = AapsLock()

        assertEquals("ran", lock.tryWithLock { "ran" })
    }

    @Test
    fun `tryWithLock releases the lock when the block throws`() {
        val lock = AapsLock()

        runCatching { lock.tryWithLock { error("boom") } }

        // Would be false if the failed call had kept the lock.
        assertTrue(lock.tryLock(), "a throwing block must not leave the lock held")
        lock.unlock()
    }

    /**
     * Nested attempts both succeed, because the lock is reentrant.
     *
     * Written in two steps rather than one expression: `tryWithLock` returns `T?` and binds `T` to a
     * non-null type, so the inner result cannot be the outer block's value. That is the bound doing
     * its job - it is what keeps `null` meaning "the lock was busy" and nothing else.
     */
    @Test
    fun `tryWithLock nests because the lock is reentrant`() {
        val lock = AapsLock()
        var inner: String? = null

        val outer = lock.tryWithLock {
            inner = lock.tryWithLock { "two deep" }
            "one deep"
        }

        assertEquals("one deep", outer)
        assertEquals("two deep", inner)
    }

    /**
     * The safety property: while another thread holds the lock, the attempt gives up **at once** and
     * the block does not run.
     *
     * The holder parks on a real thread - `Dispatchers.Default` is multi-threaded on the JVM and on
     * Kotlin/Native alike - and spins rather than suspending, because [AapsLock] is a thread lock and
     * a suspended coroutine would hand the thread back while still owning it.
     */
    @Test
    fun `tryLock gives up at once while another thread holds the lock`() = runBlocking {
        val lock = AapsLock()
        val held = CompletableDeferred<Unit>()
        val release = AtomicBoolean(false)

        val holder = launch(Dispatchers.Default) {
            lock.lock()
            try {
                held.complete(Unit)
                @Suppress("ControlFlowWithEmptyBody")
                while (!release.load()) {
                    // Holding the lock from another thread. Nothing to do but stay here.
                }
            } finally {
                lock.unlock()
            }
        }

        withTimeout(10.seconds) { held.await() }

        val blockRan = AtomicBoolean(false)
        val startedAt = TimeSource.Monotonic.markNow()
        val result = lock.tryWithLock {
            blockRan.store(true)
            "written"
        }
        val waited = startedAt.elapsedNow()
        val plainAttempt = lock.tryLock()

        release.store(true)
        holder.join()

        assertNull(result, "the lock was held, so the attempt must report failure")
        assertFalse(blockRan.load(), "the block must not run when the lock was not taken")
        assertFalse(plainAttempt, "tryLock must report the same failure as tryWithLock")
        // The holder is still inside its section above, so anything that waited for it would sit
        // here until `release`. A quarter of a second is far longer than a failed attempt needs.
        assertTrue(waited < 250.milliseconds, "a failed attempt must not wait, but it took $waited")

        // A failed attempt takes no level, so once the holder is gone the lock is free rather than
        // held twice over by a caller that never got it.
        assertTrue(lock.tryLock(), "a failed attempt must leave nothing held")
        lock.unlock()
    }
}
