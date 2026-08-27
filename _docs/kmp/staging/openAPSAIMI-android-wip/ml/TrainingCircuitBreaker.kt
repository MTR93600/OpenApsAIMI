package app.aaps.plugins.aps.openAPSAIMI.ml

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared failure circuit breaker for the on-device AIMI ML trainers (SMB refinement, basal, T3C). After
 * [maxFailures] consecutive failures it trips OPEN for [cooldownMs] (callers skip training while open); the next
 * attempt after the cooldown is allowed again, and any success [reset]s it. Thread-safe (atomic counters) since the
 * trainers run on background threads / coroutines.
 *
 * [clock] is injectable so the cooldown behaviour is deterministically testable.
 */
internal class TrainingCircuitBreaker(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val failures = AtomicInteger(0)
    private val coolingUntilMs = AtomicLong(0L)

    fun isOpen(now: Long = clock()): Boolean =
        failures.get() >= maxFailures && now < coolingUntilMs.get()

    /**
     * Record one failure.
     * @return true iff this failure just tripped the breaker OPEN (so the caller can log it once).
     */
    fun recordFailure(): Boolean {
        val f = failures.incrementAndGet()
        if (f >= maxFailures) {
            coolingUntilMs.set(clock() + cooldownMs)
            return true
        }
        return false
    }

    /** Reset the failure count (call on a successful training pass). */
    fun reset() {
        failures.set(0)
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 3
        const val DEFAULT_COOLDOWN_MS = 6L * 60 * 60 * 1000 // 6h
    }
}
