package app.aaps.core.interfaces.concurrent

/**
 * A mutual exclusion lock that works on every target.
 *
 * `kotlin.synchronized` and `@Synchronized` are JVM only, so code that guards shared state cannot be
 * multiplatform without something like this. The contract is deliberately the same as the monitor it
 * replaces:
 *
 * - **reentrant** - the thread holding the lock may take it again without deadlocking, which is what
 *   `synchronized` does and what the existing call sites assume
 * - **blocking** - [withLock] blocks the calling thread, it does not suspend. A `Mutex` from
 *   kotlinx-coroutines is NOT a drop-in replacement: it is not reentrant, and it needs a coroutine
 *
 * On Android and the JVM the actual is a `ReentrantLock`, not the object monitor: `synchronized` is a
 * block construct and cannot be split into the separate lock and unlock calls that the inline
 * [withLock] needs. Reentrancy and blocking are the same as the monitor these call sites used before
 * they were made multiplatform, which is what matters, because the first user of this is the loop's
 * calculation cache. There is **no** timed `tryLock` here, so code built on
 * `ReentrantLock.tryLock(timeout)` or `isHeldByCurrentThread` cannot move to shared code by swapping
 * in this class.
 *
 * One lock guards one thing. Do NOT lock on an object you also reassign:
 *
 * ```
 * // wrong - the field is replaced while the OLD object is locked, so a second thread
 * // locks the NEW one and walks straight in
 * synchronized(table) { table = SomethingElse() }
 * ```
 *
 * Give the state a dedicated [AapsLock] and lock that instead; it cannot be swapped out from under
 * the callers.
 */
expect class AapsLock() {

    /** Takes the lock, blocking until it is free. Prefer [withLock], which cannot leak it. */
    fun lock()

    /** Releases one level of the lock. */
    fun unlock()
}

/**
 * Runs [action] holding the lock, and returns whatever it returns.
 *
 * Inline, so `return` from inside the block behaves exactly as it did under `synchronized` - several
 * call sites return their result from within the guarded region, and a non-inline version would not
 * compile there. Same shape as `kotlin.concurrent.withLock` for `java.util.concurrent.locks.Lock`.
 */
inline fun <T> AapsLock.withLock(action: () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}
