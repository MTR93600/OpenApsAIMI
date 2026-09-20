package app.aaps.plugins.aps.openAPSAIMI.ml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TrainingCircuitBreakerTest {

    @Test
    fun `opens only after max consecutive failures and signals the trip once`() {
        var now = 0L
        val cb = TrainingCircuitBreaker(maxFailures = 3, cooldownMs = 1_000L, clock = { now })

        assertThat(cb.isOpen()).isFalse()
        assertThat(cb.recordFailure()).isFalse() // 1
        assertThat(cb.recordFailure()).isFalse() // 2
        assertThat(cb.isOpen()).isFalse()
        assertThat(cb.recordFailure()).isTrue()  // 3 → trips open
        assertThat(cb.isOpen()).isTrue()
    }

    @Test
    fun `attempts are allowed again once the cooldown elapses`() {
        var now = 0L
        val cb = TrainingCircuitBreaker(maxFailures = 2, cooldownMs = 1_000L, clock = { now })

        cb.recordFailure()
        cb.recordFailure()
        assertThat(cb.isOpen()).isTrue()

        now = 1_001L
        assertThat(cb.isOpen()).isFalse()
    }

    @Test
    fun `reset clears the failure count`() {
        var now = 0L
        val cb = TrainingCircuitBreaker(maxFailures = 2, cooldownMs = 1_000L, clock = { now })

        cb.recordFailure()
        cb.recordFailure()
        assertThat(cb.isOpen()).isTrue()

        cb.reset()
        assertThat(cb.isOpen()).isFalse()
    }
}
