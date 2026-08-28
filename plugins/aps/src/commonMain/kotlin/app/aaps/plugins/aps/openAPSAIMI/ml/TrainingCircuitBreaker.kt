package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs

/**
 * Shared failure circuit breaker for the on-device AIMI ML trainers (SMB refinement, basal, T3C). After
 * [maxFailures] consecutive failures it trips OPEN for [cooldownMs] (callers skip training while open); the next
 * attempt after the cooldown is allowed again, and any success [reset]s it. Thread-safe (`AapsLock` around
 * counters) since the trainers run on background threads / coroutines.
 *
 * [clock] is injectable so the cooldown behaviour is deterministically testable.
 */
internal class TrainingCircuitBreaker(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = { aimiWallClockMs() },
) {

    private val lock = AapsLock()
    private var failures = 0
    private var coolingUntilMs = 0L

    fun isOpen(now: Long = clock()): Boolean = lock.withLock {
        failures >= maxFailures && now < coolingUntilMs
    }

    /**
     * Record one failure.
     * @return true iff this failure just tripped the breaker OPEN (so the caller can log it once).
     */
    fun recordFailure(): Boolean = lock.withLock {
        failures += 1
        if (failures >= maxFailures) {
            coolingUntilMs = clock() + cooldownMs
            true
        } else {
            false
        }
    }

    /** Reset the failure count (call on a successful training pass). */
    fun reset() {
        lock.withLock {
            failures = 0
        }
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 3
        const val DEFAULT_COOLDOWN_MS = 6L * 60 * 60 * 1000 // 6h
    }
}
