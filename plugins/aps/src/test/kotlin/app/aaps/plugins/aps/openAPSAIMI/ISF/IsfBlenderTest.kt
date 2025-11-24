package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.Assert.assertEquals
import org.junit.Test

class IsfBlenderTest {

    @Test
    fun `test blend rate limiting`() {
        val blender = IsfBlender(maxStepPctPerLoop = 0.05, maxStepPctPerHour = 0.20)
        val now = 1000000L
        
        // Initial blend: 50% fused(50), 50% kalman(100) -> 75
        val result1 = blender.blend(50.0, 100.0, 0.5, now)
        assertEquals(75.0, result1, 0.01)
        
        // Immediate next call with same time: should be clamped to previous
        // Even if inputs change drastically
        val result2 = blender.blend(100.0, 200.0, 0.5, now)
        assertEquals(75.0, result2, 0.01)
        
        // 1 hour later: allowed 20% change.
        // 75 * 1.2 = 90.
        // Target 150 (blend of 100, 200).
        // Should be clamped to 90.
        val later = now + 3600000L
        val result3 = blender.blend(100.0, 200.0, 0.5, later)
        assertEquals(90.0, result3, 0.01)
    }
}
