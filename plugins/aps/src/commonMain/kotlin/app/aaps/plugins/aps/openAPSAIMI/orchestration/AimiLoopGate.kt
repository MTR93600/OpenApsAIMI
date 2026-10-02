package app.aaps.plugins.aps.openAPSAIMI.orchestration

/**
 * Phase E: serialized ingress for AIMI `determine_basal`. Acquired/released from [AimiLoopTelemetry]
 * so non-local returns inside the inlined tick body remain valid.
 */
internal object AimiLoopGate {

    /** Max wait when a prior tick is still holding the exclusive lock (avoid indefinite stall). */
    const val DEFAULT_ACQUIRE_TIMEOUT_MS = 45_000L

    private val invocationLock = AimiExclusiveLock()

    fun tryAcquireExclusive(timeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS): Boolean =
        invocationLock.tryLock(timeoutMs)

    fun releaseExclusive() {
        invocationLock.unlockIfHeld()
    }
}
