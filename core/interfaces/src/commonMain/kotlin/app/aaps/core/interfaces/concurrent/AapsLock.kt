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
 * calculation cache.
 *
 * [tryLock] adds the one thing a monitor cannot express: an attempt that gives up **at once** instead
 * of waiting. Some call sites run on the decision thread and must never queue behind a slow holder -
 * there, skipping the work is correct and waiting for it is not.
 *
 * Still **not** here, and still a reason a call site cannot move to shared code by swapping in this
 * class:
 *
 * - a timed `tryLock(timeout)` - it needs a duration type in the contract and a bounded wait on every
 *   actual, and `NSRecursiveLock` expresses that as `lockBeforeDate`, which is a different shape
 * - `isHeldByCurrentThread` - `NSRecursiveLock` does not publish its owner at all, so an `actual`
 *   would have to track the owning thread itself
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

    /**
     * Takes the lock only if it is free **right now**, and returns whether it was taken.
     *
     * Never waits, not even briefly. Returning `false` means another thread holds it and the caller
     * must do something else; it does not mean "try again in a moment".
     *
     * Reentrant like [lock]: a thread that already holds the lock always gets `true` and takes one
     * more level, so it must [unlock] once per successful attempt. Prefer [tryWithLock], which does
     * that for you and cannot run the block on a failed attempt.
     */
    fun tryLock(): Boolean

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

/**
 * Runs [action] only if the lock is free **right now**, and returns `null` otherwise.
 *
 * For callers that must not wait. The block does not run at all on a failed attempt - that is the
 * point of it, not a detail: the caller is expected to skip the work, not to do it unguarded. The
 * lock is released in a `finally`, so a throwing block does not leave it held.
 *
 * [T] is bound to a non-null type so `null` can only ever mean "the lock was busy".
 *
 * Inline, for the same reason as [withLock].
 */
inline fun <T : Any> AapsLock.tryWithLock(action: () -> T): T? {
    if (!tryLock()) return null
    try {
        return action()
    } finally {
        unlock()
    }
}
