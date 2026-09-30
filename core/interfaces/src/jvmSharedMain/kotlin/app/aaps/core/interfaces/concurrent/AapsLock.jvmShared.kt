package app.aaps.core.interfaces.concurrent

import java.util.concurrent.locks.ReentrantLock

/**
 * Android/JVM: [ReentrantLock], which has the same reentrancy and blocking behaviour as the monitor
 * these call sites used before they were made multiplatform.
 *
 * A monitor would fit the semantics even better, but `synchronized` is a block construct - it cannot
 * be split into the separate lock/unlock calls the inline `withLock` needs.
 */
actual class AapsLock actual constructor() {

    private val delegate = ReentrantLock()

    actual fun lock() = delegate.lock()

    /**
     * `ReentrantLock.tryLock()` - the zero-argument one, which "acquires the lock only if it is not
     * held by another thread at the time of invocation" and returns immediately either way. The timed
     * overload would wait, so it is deliberately not the one called here.
     */
    actual fun tryLock(): Boolean = delegate.tryLock()

    actual fun unlock() = delegate.unlock()
}
