package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The read-ahead cache the tick uses for every slow read: one value, plus a guard that keeps a
 * second refresh from starting while the first is still running.
 *
 * The reference writes this shape out by hand thirteen times, once per cached read, always the same
 * five lines:
 *
 * ```
 * if (!xRefreshInFlight.compareAndSet(false, true)) return
 * determineIoScope.launch {
 *     try { xRef.set(<slow read>) }
 *     catch (_: Exception) { xRef.set(<fallback>) }
 *     finally { xRefreshInFlight.set(false) }
 * }
 * ```
 *
 * **This cache never waits.** [refresh] starts the read and returns at once, so the [get] that
 * follows it in the same tick hands back the value the *previous* tick stored, or [initial] on the
 * very first call. That staleness is the design, not a defect: the decision thread must not block on
 * a database. Any change here that made [get] await the read would move a value by one tick, and
 * several of these values reach the ISF, so it would change a dose.
 *
 * The guard is released inside the coroutine's `finally`, which means it stays taken for the whole
 * duration of the slow read. A caller that arrives meanwhile is told nothing and simply reads the
 * older value. That is what keeps thirteen caches from queueing thirteen database reads per tick.
 *
 * The decision of *what* to read and *what* to fall back to stays with the caller. This class holds
 * no clinical rule; it only owns the value, the guard and the launch.
 *
 * `kotlin.concurrent.atomics` is still experimental in Kotlin 2.4.10, so the opt-in below is needed.
 * It is kept on this class rather than set as a compiler flag, so that the experimental API stays
 * inside this one file and the thirteen call sites do not have to opt in to anything.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class AimiSingleFlightCache<T>(private val initial: T) {

    private val value = AtomicReference(initial)
    private val inFlight = AtomicBoolean(false)

    /** The value stored by the last refresh that finished, or `initial` if none has. Never blocks. */
    fun get(): T = value.load()

    /**
     * Writes the value without going through a refresh.
     *
     * Only for the one place the reference does this: the trajectory refresh stores the effective
     * profile it had to read anyway, so the profile cache does not read it a second time.
     */
    fun set(newValue: T) = value.store(newValue)

    /** Whether a refresh is running right now. For tests; the tick never asks. */
    fun isRefreshing(): Boolean = inFlight.load()

    /**
     * Starts a refresh unless one is already running, and returns at once either way.
     *
     * [load] runs on [scope], so the dispatcher is the scope's. [onFailure] produces the value to
     * store when [load] throws; it is evaluated inside the `catch`, so a fallback built from the
     * caller's arguments is computed at the same moment as in the reference, not earlier.
     *
     * Only [Exception] is caught, as in the reference. An `Error` escapes to the scope's handler.
     */
    fun refresh(scope: CoroutineScope, load: suspend () -> T, onFailure: () -> T) {
        if (inFlight.load()) return
        inFlight.store(true)
        scope.launch {
            try {
                value.store(load())
            } catch (_: Exception) {
                value.store(onFailure())
            } finally {
                inFlight.store(false)
            }
        }
    }
}
