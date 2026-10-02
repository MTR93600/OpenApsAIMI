package app.aaps.plugins.aps.openAPSAIMI.orchestration

/**
 * The timed, reentrant lock behind [AimiLoopGate], on every target.
 *
 * `app.aaps.core.interfaces.concurrent.AapsLock` is the usual replacement for `synchronized`, but it
 * has no timed attempt and no way to ask whether the calling thread holds it, and the gate needs both:
 * it waits a bounded time for a previous tick instead of giving up at once, and it releases only when
 * this thread really took the lock. So the gate keeps its own small lock rather than changing what it
 * does.
 *
 * The contract is the one `java.util.concurrent.locks.ReentrantLock` already gave the gate:
 *
 * - **reentrant** - a thread that already holds it takes one more level and succeeds at once
 * - **timed** - [tryLock] waits up to `timeoutMs` and then gives up
 * - **owner aware** - [unlockIfHeld] releases one level only when the calling thread holds it, and
 *   does nothing otherwise, so a release without a matching acquire cannot throw
 */
internal expect class AimiExclusiveLock() {

    /**
     * Takes one level of the lock, waiting at most [timeoutMs] milliseconds, and returns whether it
     * was taken. A negative wait is read as no wait at all.
     */
    fun tryLock(timeoutMs: Long): Boolean

    /** Releases one level, but only if the calling thread holds the lock. */
    fun unlockIfHeld()
}
