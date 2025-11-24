package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.Assert.assertEquals
import org.junit.Test

class IsfFusionTest {

    @Test
    fun `test fused logic`() {
        val fusion = IsfFusion(IsfFusionBounds(minFactor = 0.5, maxFactor = 1.5, maxChangePer5Min = 0.1))
        
        // Case 1: All equal
        assertEquals(50.0, fusion.fused(50.0, 50.0, 1.0), 0.01)
        
        // Case 2: Median logic
        // candidates: 40, 50, 60 -> median 50
        assertEquals(50.0, fusion.fused(40.0, 50.0, 1.2), 0.01) // 50*1.2 = 60
        
        // Case 3: Bounds clamping (minFactor)
        // tddIsf = 50. min = 25. max = 75.
        // candidates: 10 (profile), 50 (tdd), 100 (pkpd) -> median 50.
        // 50 is within bounds.
        assertEquals(50.0, fusion.fused(10.0, 50.0, 2.0), 0.01)
        
        // Case 4: Bounds clamping (maxFactor)
        // tddIsf = 50. max = 75.
        // candidates: 80, 90, 100 -> median 90.
        // 90 > 75 -> clamped to 75.
        // Wait, median of (80, 50, 100) -> 80.
        // 80 > 75 -> 75.
        assertEquals(75.0, fusion.fused(80.0, 50.0, 2.0), 0.01)
    }

    @Test
    fun `test fused smoothing`() {
        val fusion = IsfFusion(IsfFusionBounds(maxChangePer5Min = 0.1))
        
        // Initial: 50
        assertEquals(50.0, fusion.fused(50.0, 50.0, 1.0), 0.01)
        
        // Next: Target 100. Max change 10% -> 55.
        // candidates: 100, 100, 100 -> median 100.
        // clamped to 50 * 1.1 = 55.
        assertEquals(55.0, fusion.fused(100.0, 100.0, 1.0), 0.01)
    }
}
