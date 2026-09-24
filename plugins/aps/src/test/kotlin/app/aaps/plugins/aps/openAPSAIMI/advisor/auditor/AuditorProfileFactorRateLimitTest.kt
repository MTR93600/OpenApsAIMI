package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The profile check is a second call on the same provider key as the audit verdict, and that verdict
 * moves real doses in soft modulation. One call every 15 minutes is the whole rule.
 */
class AuditorProfileFactorRateLimitTest {

    private val minute = 60_000L

    @Test
    fun `the very first request is always allowed`() {
        assertTrue(AuditorProfileFactorRateLimit.allow(nowMs = 1_000_000L, lastRequestMs = 0L))
    }

    @Test
    fun `a second request inside 15 minutes is skipped`() {
        val last = 1_000_000L
        assertFalse(AuditorProfileFactorRateLimit.allow(last + 1, last))
        assertFalse(AuditorProfileFactorRateLimit.allow(last + 5 * minute, last))
        assertFalse(AuditorProfileFactorRateLimit.allow(last + 15 * minute - 1, last))
    }

    @Test
    fun `exactly 15 minutes later is allowed again`() {
        val last = 1_000_000L
        assertTrue(AuditorProfileFactorRateLimit.allow(last + 15 * minute, last))
        assertTrue(AuditorProfileFactorRateLimit.allow(last + 60 * minute, last))
    }

    @Test
    fun `a clock that jumped backwards does not lock the request out for ever`() {
        val last = 10_000_000L
        assertTrue(AuditorProfileFactorRateLimit.allow(last - 60 * minute, last))
    }
}
