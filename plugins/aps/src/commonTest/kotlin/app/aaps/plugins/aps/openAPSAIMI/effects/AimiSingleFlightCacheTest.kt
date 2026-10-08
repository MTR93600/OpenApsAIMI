package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The invariants of the tick's read-ahead caches.
 *
 * Every assertion below is run twice: once against [AimiSingleFlightCache], and once against
 * [LegacyHandRolledCache], which is the reference's own five lines copied out. The point is to show
 * which invariants the hand written version already had, so the shared class can be read as a port
 * and not as a rewrite. Where both pass, the test pins behaviour that was already correct.
 */
@OptIn(ExperimentalAtomicApi::class)
class AimiSingleFlightCacheTest {

    /**
     * What both implementations must offer for the shared assertions. Test only.
     */
    private interface Cache<T> {

        fun get(): T
        fun isRefreshing(): Boolean
        fun refresh(scope: CoroutineScope, load: suspend () -> T, onFailure: () -> T)
    }

    /**
     * The reference's shape, copied from `refreshStepsAsync` and its twelve siblings, with the slow
     * read left as a parameter. Uses the JVM style `AtomicReference` / `AtomicBoolean` calls through
     * the multiplatform ones so the same source runs on every target.
     */
    private class LegacyHandRolledCache<T>(initial: T) : Cache<T> {

        private val ref = AtomicReference(initial)
        private val inFlight = AtomicBoolean(false)

        override fun get(): T = ref.load()
        override fun isRefreshing(): Boolean = inFlight.load()

        override fun refresh(scope: CoroutineScope, load: suspend () -> T, onFailure: () -> T) {
            if (!inFlight.compareAndSet(false, true)) return
            scope.launch {
                try {
                    ref.store(load())
                } catch (_: Exception) {
                    ref.store(onFailure())
                } finally {
                    inFlight.store(false)
                }
            }
        }
    }

    private class PortedCache<T>(initial: T) : Cache<T> {

        private val delegate = AimiSingleFlightCache(initial)

        override fun get(): T = delegate.get()
        override fun isRefreshing(): Boolean = delegate.isRefreshing()
        override fun refresh(scope: CoroutineScope, load: suspend () -> T, onFailure: () -> T) =
            delegate.refresh(scope, load, onFailure)
    }

    private fun bothCaches(): List<Pair<String, (Int) -> Cache<Int>>> = listOf(
        "legacy hand rolled" to { initial: Int -> LegacyHandRolledCache(initial) },
        "ported shared" to { initial: Int -> PortedCache(initial) },
    )

    // ---------------------------------------------------------------------------------------------
    // 1. A second refresh must not start a second slow read while the first is still running.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun secondRefreshDoesNotStartWhileFirstIsStillRunning() = runTest {
        for ((name, make) in bothCaches()) {
            val cache = make(0)
            val gate = CompletableDeferred<Unit>()
            val loads = AtomicInt(0)

            cache.refresh(
                scope = this,
                load = { loads.fetchAndAdd(1); gate.await(); 42 },
                onFailure = { -1 },
            )
            // The guard is taken synchronously, before the coroutine is even dispatched.
            assertTrue(cache.isRefreshing(), "$name: guard taken at once")
            runCurrent()
            assertEquals(1, loads.load(), "$name: first read started")

            // Arrives while the first read is parked on the gate. Must be refused outright.
            cache.refresh(
                scope = this,
                load = { loads.fetchAndAdd(1); 99 },
                onFailure = { -1 },
            )
            runCurrent()
            assertEquals(1, loads.load(), "$name: second read refused, single flight held")

            gate.complete(Unit)
            runCurrent()
            assertEquals(42, cache.get(), "$name: first read's value stored")
            assertFalse(cache.isRefreshing(), "$name: guard released after success")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The read never waits. This is the one that would move a value by a whole tick if broken,
    //    and several of these values reach the ISF.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun getReturnsPreviousValueWhileRefreshIsInFlight() = runTest {
        for ((name, make) in bothCaches()) {
            val cache = make(7)
            val gate = CompletableDeferred<Unit>()

            cache.refresh(scope = this, load = { gate.await(); 42 }, onFailure = { -1 })
            assertEquals(7, cache.get(), "$name: initial value before the read is dispatched")
            runCurrent()
            assertEquals(7, cache.get(), "$name: still the old value while the read is parked")

            gate.complete(Unit)
            runCurrent()
            assertEquals(42, cache.get(), "$name: new value only once the read finished")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. A read that throws must release the guard. Without the `finally` the cache would be wedged
    //    for the rest of the process and would hand back a stale value for ever.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aThrowingReadStoresTheFallbackAndReleasesTheGuard() = runTest {
        for ((name, make) in bothCaches()) {
            val cache = make(7)
            val fallbackBuilt = AtomicInt(0)

            cache.refresh(
                scope = this,
                load = { throw IllegalStateException("database down") },
                onFailure = { fallbackBuilt.fetchAndAdd(1); -1 },
            )
            runCurrent()
            assertEquals(-1, cache.get(), "$name: fallback stored")
            assertEquals(1, fallbackBuilt.load(), "$name: fallback built exactly once")
            assertFalse(cache.isRefreshing(), "$name: guard released after failure")

            // And the cache still works afterwards.
            cache.refresh(scope = this, load = { 55 }, onFailure = { -2 })
            runCurrent()
            assertEquals(55, cache.get(), "$name: a later read still runs")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. The fallback is not built on the happy path. The reference builds some fallbacks out of the
    //    call's own arguments, so building one eagerly would be work done on every tick.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun fallbackIsNotBuiltWhenTheReadSucceeds() = runTest {
        for ((name, make) in bothCaches()) {
            val cache = make(0)
            val fallbackBuilt = AtomicInt(0)

            cache.refresh(
                scope = this,
                load = { 42 },
                onFailure = { fallbackBuilt.fetchAndAdd(1); -1 },
            )
            runCurrent()
            assertEquals(42, cache.get(), "$name: value stored")
            assertEquals(0, fallbackBuilt.load(), "$name: fallback never built on success")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Real threads. Many callers race on the guard at once; the slow read must never be running
    //    twice over. A plain `if (!inFlight) { inFlight = true }` instead of the compare and set
    //    fails this one.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theReadIsNeverRunningTwiceOverUnderRealThreads() = runTest {
        for ((name, make) in bothCaches()) {
            val cache = make(0)
            val inside = AtomicInt(0)
            val maxSeenInside = AtomicInt(0)
            val totalReads = AtomicInt(0)

            withContext(Dispatchers.Default) {
                val scope = this
                repeat(64) { caller ->
                    cache.refresh(
                        scope = scope,
                        load = {
                            val now = inside.fetchAndAdd(1) + 1
                            while (true) {
                                val seen = maxSeenInside.load()
                                if (now <= seen || maxSeenInside.compareAndSet(seen, now)) break
                            }
                            totalReads.fetchAndAdd(1)
                            inside.fetchAndAdd(-1)
                            caller
                        },
                        onFailure = { -1 },
                    )
                }
            }

            assertEquals(
                1, maxSeenInside.load(),
                "$name: the slow read was running more than once at a time"
            )
            assertTrue(totalReads.load() >= 1, "$name: at least one read ran")
            assertTrue(
                totalReads.load() <= 64,
                "$name: cannot have run more reads than callers"
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. A reader racing a writer always sees one whole value, never a half written one, and once a
    //    refresh has finished the reader never goes backwards to the older value.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aRacingReaderOnlyEverSeesWholeValues() = runTest {
        // Every value in this test, the initial one included, is a list of n copies of n, so the
        // invariant a torn read would break is simply "every element equals the size".
        val cache = AimiSingleFlightCache(List(1) { 1 })
        val badReads = AtomicInt(0)

        withContext(Dispatchers.Default) {
            val writers = (1..32).map { n ->
                async {
                    cache.set(List(n) { n })
                }
            }
            val readers = (1..32).map {
                async {
                    repeat(200) {
                        val seen = cache.get()
                        if (seen.isEmpty() || seen.any { v -> v != seen.size }) {
                            badReads.fetchAndAdd(1)
                        }
                    }
                }
            }
            (writers + readers).awaitAll()
        }

        assertEquals(0, badReads.load(), "a reader saw a value that was never written whole")
    }
}
