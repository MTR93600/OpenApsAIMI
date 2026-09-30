package app.aaps.core.interfaces.concurrent

import platform.Foundation.NSRecursiveLock

/**
 * iOS: `NSRecursiveLock` rather than `NSLock`, because a monitor is reentrant and the call sites
 * being ported rely on that.
 */
actual class AapsLock actual constructor() {

    private val delegate = NSRecursiveLock()

    actual fun lock() = delegate.lock()

    /**
     * `NSRecursiveLock.tryLock()` - documented as "attempts to acquire a lock, and immediately
     * returns a Boolean value that indicates whether the attempt was successful". Immediately is the
     * word that matters: the waiting form is `lockBeforeDate`, which is a different method and is not
     * called here.
     *
     * Recursive like the JVM `ReentrantLock.tryLock()`, because the lock itself is recursive - a
     * thread that already owns it takes one more level and gets `true`, rather than failing against
     * itself. `AapsLockTryLockTest` pins both halves on this target.
     */
    actual fun tryLock(): Boolean = delegate.tryLock()

    actual fun unlock() = delegate.unlock()
}
