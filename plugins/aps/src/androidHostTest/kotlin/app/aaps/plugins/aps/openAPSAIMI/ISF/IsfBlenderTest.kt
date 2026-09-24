package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IsfBlenderTest {

    @Test
    fun `test blend rate limiting`() {
        val blender = IsfBlender(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L

        // Initial blend: 50% fused(50), 50% kalman(100) -> 75
        // There is no anchor yet, so the rate limiter is skipped.
        val result1 = blender.blend(50.0, 100.0, 0.5, now)
        assertEquals(75.0, result1, 0.01)

        // Immediate next call with same timestamp: zero elapsed budget, clamped to previous.
        val result2 = blender.blend(100.0, 200.0, 0.5, now)
        assertEquals(75.0, result2, 0.01)

        // 1 hour later: budget = min(5%/loop, 20%/h * 1h) = 5% per call.
        // Target 150 (blend of 100, 200) clamped to 75 * 1.05 = 78.75.
        val later = now + 3600000L
        val result3 = blender.blend(100.0, 200.0, 0.5, later)
        assertEquals(78.75, result3, 0.01)
    }

    /** B1: the very first call of the process must not be pinned to the slow ISF. */
    @Test
    fun `first blend is not rate limited`() {
        val blender = IsfBlender(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L

        // 0.5 * 50 + 0.5 * 100 = 75, returned as is because there is no anchor.
        val result = blender.blend(fusedIsf = 50.0, kalmanIsf = 100.0, trustFast = 0.5, nowMs = now)

        assertEquals(75.0, result, 0.01)
    }

    /** B2: once an anchor exists the step stays inside the elapsed-time budget. */
    @Test
    fun `blend is rate limited once an anchor exists`() {
        val blender = IsfBlender(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L
        val oneTickMs = 300000L

        val first = blender.blend(fusedIsf = 50.0, kalmanIsf = 100.0, trustFast = 0.5, nowMs = now)
        // Ask for a much higher ISF one nominal tick later.
        val second = blender.blend(fusedIsf = 100.0, kalmanIsf = 200.0, trustFast = 0.5, nowMs = now + oneTickMs)

        // Budget = min(0.05, 0.20 * 5/60) = 0.0166667, so the step is capped at +1.66667%.
        assertEquals(first * (1.0 + 0.20 * 5.0 / 60.0), second, 0.01)
    }

    /** B4: the budget is measured from the last call, not from the first one. */
    @Test
    fun `anchor keeps value and timestamp together`() {
        val blender = IsfBlender(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L

        blender.blend(fusedIsf = 50.0, kalmanIsf = 100.0, trustFast = 0.5, nowMs = now)
        val second = blender.blend(fusedIsf = 100.0, kalmanIsf = 200.0, trustFast = 0.5, nowMs = now + 60000L)
        val third = blender.blend(fusedIsf = 100.0, kalmanIsf = 200.0, trustFast = 0.5, nowMs = now + 300000L)

        // Only 4 minutes passed since the second call, so the budget is 20% * 4/60, not 20% * 5/60.
        assertEquals(second * (1.0 + 0.20 * 4.0 / 60.0), third, 0.01)
    }
}
