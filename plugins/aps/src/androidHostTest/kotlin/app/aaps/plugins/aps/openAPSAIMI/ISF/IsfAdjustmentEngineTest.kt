package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IsfAdjustmentEngineTest {

    @Test
    fun `test compute rate limiting`() {
        val engine = IsfAdjustmentEngine(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L
        
        // Initial call
        val result1 = engine.compute(
            bgKalman = 150.0,
            tddEma = 40.0,
            profileIsf = 50.0,
            sippConfidence = 0.0,
            kalmanVar = 0.0,
            nowMs = now
        )
        // Should be close to calculated value, but let's check rate limit on second call
        
        // Second call 1 hour later
        val later = now + 3600000L
        // Force a huge change in input to trigger rate limit
        // If we pass very high BG or TDD, ISF calculation changes.
        
        // Let's just test rateLimit logic via compute.
        // If we call it immediately with same timestamp, elapsed is 0. allowedPct is 0.
        // Should return previous value.
        val result2 = engine.compute(
            bgKalman = 200.0, // Different input
            tddEma = 40.0,
            profileIsf = 50.0,
            sippConfidence = 0.0,
            kalmanVar = 0.0,
            nowMs = now // Same time
        )
        assertEquals(result1, result2, 0.001)
    }

    /** B3: the very first call of the process must not be pinned to the profile ISF. */
    @Test
    fun `first adjustment is not rate limited`() {
        val engine = IsfAdjustmentEngine(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)

        val result = engine.compute(
            bgKalman = 120.0,
            tddEma = 40.0,
            profileIsf = 50.0,
            sippConfidence = 0.0,
            kalmanVar = 0.0,
            nowMs = 1000000L
        )

        // denom = ln(120/55) * 40^2 * 0.02 = 24.965, so 2300/denom = 92.13, capped at 1.7 * 50 = 85.
        // kalmanTrust = 1, wAf = 0.6, so blended = 0.6 * 85 + 0.4 * 50 = 71.
        // There is no anchor yet, so 71 is returned as is instead of the profile ISF.
        assertEquals(71.0, result, 0.01)
    }
}
