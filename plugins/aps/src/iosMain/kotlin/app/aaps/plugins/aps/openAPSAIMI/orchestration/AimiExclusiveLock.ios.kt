package app.aaps.plugins.aps.openAPSAIMI.orchestration

import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.concurrent.Volatile

/**
 * iOS: `NSRecursiveLock`, because the gate relies on a reentrant lock. `lockBeforeDate` is its waiting
 * form, so it gives the same bounded wait as `ReentrantLock.tryLock(timeout)` on the other targets.
 *
 * `NSRecursiveLock` does not publish its owner, so the owning thread and the level count are kept
 * here. Both are only written by the thread that holds the lock, which is also the only thread allowed
 * to release it, so [unlockIfHeld] matches the `isHeldByCurrentThread` test it stands in for.
 */
internal actual class AimiExclusiveLock actual constructor() {

    private val delegate = NSRecursiveLock()

    @Volatile
    private var owner: NSThread? = null

    @Volatile
    private var heldLevels: Int = 0

    actual fun tryLock(timeoutMs: Long): Boolean {
        val seconds = timeoutMs.coerceAtLeast(0L).toDouble() / 1000.0
        val taken = delegate.lockBeforeDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
        if (taken) {
            owner = NSThread.currentThread
            heldLevels += 1
        }
        return taken
    }

    actual fun unlockIfHeld() {
        if (owner !== NSThread.currentThread) return
        val left = heldLevels - 1
        heldLevels = left
        if (left <= 0) {
            owner = null
        }
        delegate.unlock()
    }
}
