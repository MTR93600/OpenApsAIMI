package app.aaps.plugins.aps.openAPSAIMI.orchestration

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Phase E: serialized ingress for AIMI [determine_basal]. Acquired/released from [AimiLoopTelemetry]
 * so non-local returns inside the inlined tick body remain valid.
 */
internal object AimiLoopGate {

    /** Max wait when a prior tick is still holding the exclusive lock (avoid indefinite stall). */
    const val DEFAULT_ACQUIRE_TIMEOUT_MS = 45_000L

    private val invocationLock = ReentrantLock()

    fun tryAcquireExclusive(timeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS): Boolean =
        invocationLock.tryLock(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

    fun releaseExclusive() {
        if (invocationLock.isHeldByCurrentThread) {
            invocationLock.unlock()
        }
    }
}
