package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the on-device trainer circuit breaker: trip after N consecutive failures, cooldown, reset.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `fe96b64f12`
 * (`plugins/aps/src/test/.../ml/TrainingCircuitBreakerTest.kt`). Study source set:
 * [TrainingCircuitBreaker] is commonMain (`AapsLock` + injectable clock), so the tests live in
 * `commonTest` (`kotlin.test`) — same layout as [SmbTrainingRowBufferTest], not `androidHostTest`.
 *
 * Adaptations: `org.junit.jupiter` + Truth → `kotlin.test`. Clock injection is already on study
 * (same as ref). No File, no Hilt, no production change.
 */
class TrainingCircuitBreakerTest {

    @Test
    fun `opens only after max consecutive failures and signals the trip once`() {
        var now = 0L
        val cb = TrainingCircuitBreaker(maxFailures = 3, cooldownMs = 1_000L, clock = { now })

        assertFalse(cb.isOpen())
        assertFalse(cb.recordFailure()) // 1
        assertFalse(cb.recordFailure()) // 2
        assertFalse(cb.isOpen())
        assertTrue(cb.recordFailure())  // 3 → trips open
        assertTrue(cb.isOpen())
    }

    @Test
    fun `attempts are allowed again once the cooldown elapses`() {
        var now = 0L
        val cb = TrainingCircuitBreaker(maxFailures = 2, cooldownMs = 1_000L, clock = { now })

        cb.recordFailure()
        cb.recordFailure()
        assertTrue(cb.isOpen())

        now = 1_001L
        assertFalse(cb.isOpen())
    }

    @Test
    fun `reset clears the failure count`() {
        var now = 0L
        val cb = TrainingCircuitBreaker(maxFailures = 2, cooldownMs = 1_000L, clock = { now })

        cb.recordFailure()
        cb.recordFailure()
        assertTrue(cb.isOpen())

        cb.reset()
        assertFalse(cb.isOpen())
    }
}
