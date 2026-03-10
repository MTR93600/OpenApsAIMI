package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkPdCoreTest {

    private val kernel = LogNormalKernel()
    private val params = PkPdParams(diaHrs = 5.0, peakMin = 75.0)

    @Test
    fun `test LogNormalKernel cdf properties`() {
        // CDF at 0 should be 0
        assertEquals(0.0, kernel.cdf(0.0, params), 0.001)
        
        // CDF at DIA should be close to 1 (normalized)
        // Wait, cdf in kernel is raw, normalizedCdf handles normalization?
        // Let's check kernel.cdf implementation:
        // return 0.5 * (1 + erf(z)) -> standard lognormal CDF.
        // It doesn't guarantee 1.0 at DIA.
        // normalizedCdf extension function does.
        
        val normCdfAtDia = kernel.normalizedCdf(5.0 * 60.0, params)
        assertEquals(1.0, normCdfAtDia, 0.001)
        
        // CDF should be increasing
        val t1 = 60.0
        val t2 = 120.0
        assertTrue(kernel.cdf(t2, params) > kernel.cdf(t1, params))
    }

    @Test
    fun `test LogNormalKernel actionAt properties`() {
        // Action should be positive
        assertTrue(kernel.actionAt(60.0, params) > 0.0)
        
        // Action at 0 should be 0
        assertEquals(0.0, kernel.actionAt(0.0, params), 0.001)
    }

    @Test
    fun `test findTimeForNormalizedCdf`() {
        // 50% of action
        val t50 = kernel.findTimeForNormalizedCdf(0.5, params)
        val cdfAtT50 = kernel.normalizedCdf(t50, params)
        assertEquals(0.5, cdfAtT50, 0.01)
    }
}
