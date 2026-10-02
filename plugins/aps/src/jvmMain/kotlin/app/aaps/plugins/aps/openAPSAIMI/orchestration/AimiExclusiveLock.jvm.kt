package app.aaps.plugins.aps.openAPSAIMI.orchestration

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * JVM: `ReentrantLock`, which is what the gate used before it was made multiplatform. Both calls
 * are the ones it made then, so the wait and the ownership rule are unchanged.
 */
internal actual class AimiExclusiveLock actual constructor() {

    private val delegate = ReentrantLock()

    actual fun tryLock(timeoutMs: Long): Boolean =
        delegate.tryLock(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

    actual fun unlockIfHeld() {
        if (delegate.isHeldByCurrentThread) {
            delegate.unlock()
        }
    }
}
