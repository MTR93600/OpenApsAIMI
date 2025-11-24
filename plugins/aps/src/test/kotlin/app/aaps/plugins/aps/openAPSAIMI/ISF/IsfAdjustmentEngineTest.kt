package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
