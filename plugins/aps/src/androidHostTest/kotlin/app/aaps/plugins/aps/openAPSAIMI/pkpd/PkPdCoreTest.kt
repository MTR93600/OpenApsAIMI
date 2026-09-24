package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PkPdCoreTest {

    private val kernel = ExponentialKernel()
    private val params = PkPdParams(diaHrs = 5.0, peakMin = 75.0)

    @Test
    fun `test ExponentialKernel cdf properties`() {
        // CDF at 0 should be 0
        assertEquals(0.0, kernel.cdf(0.0, params), 0.001)

        // The kernel is fully absorbed at DIA, so the normalized CDF is 1 there.
        val normCdfAtDia = kernel.normalizedCdf(5.0 * 60.0, params)
        assertEquals(1.0, normCdfAtDia, 0.001)

        // CDF should be increasing
        val t1 = 60.0
        val t2 = 120.0
        assertTrue(kernel.cdf(t2, params) > kernel.cdf(t1, params))
    }

    @Test
    fun `test ExponentialKernel actionAt properties`() {
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

    @Test
    fun `iob residual matches the oref reference curve at peak 55`() {
        val p = PkPdParams(diaHrs = 5.0, peakMin = 55.0)
        assertEquals(0.668, kernel.iobResidual(60.0, p), 0.02)
        assertEquals(0.288, kernel.iobResidual(120.0, p), 0.02)
        assertEquals(0.088, kernel.iobResidual(180.0, p), 0.02)
        assertEquals(0.015, kernel.iobResidual(240.0, p), 0.02)
    }

    /**
     * The old log-normal kernel took its shape from the peak only, so DIA moved the curve by
     * about 1e-5 and the learning loop could never reduce its error by changing DIA. A longer
     * DIA must leave clearly more insulin on board in the tail.
     */
    @Test
    fun `dia really changes the curve`() {
        val short = PkPdParams(diaHrs = 5.0, peakMin = 55.0)
        val long = PkPdParams(diaHrs = 7.0, peakMin = 55.0)
        val residualShort = kernel.iobResidual(180.0, short)
        val residualLong = kernel.iobResidual(180.0, long)
        assertTrue(
            residualLong - residualShort >= 0.03,
            "DIA 7 h must leave at least 0.03 more IOB at 180 min than DIA 5 h, got " +
                "$residualLong vs $residualShort"
        )
        // The same must hold for the instant action the estimator learns on.
        assertTrue(
            kernel.actionAt(180.0, long) > kernel.actionAt(180.0, short),
            "action at 180 min must grow with DIA"
        )
    }

    @Test
    fun `action integrates to one over the whole dia`() {
        for (diaHrs in listOf(4.0, 5.0, 6.5, 8.0, 12.0)) {
            for (peakMin in listOf(35.0, 55.0, 75.0, 120.0)) {
                val p = PkPdParams(diaHrs, peakMin)
                val diaMin = diaHrs * 60.0
                val steps = 20_000
                val step = diaMin / steps
                var area = 0.0
                for (i in 0 until steps) {
                    area += kernel.actionAt((i + 0.5) * step, p) * step
                }
                assertEquals(1.0, area, 0.02, "area for dia=$diaHrs peak=$peakMin")
            }
        }
    }

    @Test
    fun `kernel stays finite and in range over the full parameter sweep`() {
        var diaHrs = 4.0
        while (diaHrs <= 16.0) {
            var peakMin = 35.0
            while (peakMin <= 200.0) {
                val p = PkPdParams(diaHrs, peakMin)
                var t = 0.0
                var previousResidual = 1.0
                while (t <= diaHrs * 60.0 + 60.0) {
                    val residual = kernel.iobResidual(t, p)
                    val action = kernel.actionAt(t, p)
                    val cdf = kernel.cdf(t, p)
                    assertTrue(residual.isFinite(), "residual not finite at dia=$diaHrs peak=$peakMin t=$t")
                    assertTrue(action.isFinite(), "action not finite at dia=$diaHrs peak=$peakMin t=$t")
                    assertTrue(cdf.isFinite(), "cdf not finite at dia=$diaHrs peak=$peakMin t=$t")
                    assertTrue(residual in 0.0..1.0, "residual $residual out of range at dia=$diaHrs peak=$peakMin t=$t")
                    assertTrue(cdf in 0.0..1.0, "cdf $cdf out of range at dia=$diaHrs peak=$peakMin t=$t")
                    assertTrue(action >= 0.0, "negative action at dia=$diaHrs peak=$peakMin t=$t")
                    assertTrue(
                        residual <= previousResidual + 1e-9,
                        "residual grew at dia=$diaHrs peak=$peakMin t=$t"
                    )
                    previousResidual = residual
                    t += 5.0
                }
                peakMin += 5.0
            }
            diaHrs += 0.5
        }
    }

    @Test
    fun `peak above half of dia stays safe`() {
        // The RAPID preset allows a peak of 130 min, and a very short DIA could cross DIA/2.
        val p = PkPdParams(diaHrs = 4.0, peakMin = 200.0)
        assertTrue(kernel.iobResidual(60.0, p).isFinite())
        assertTrue(kernel.iobResidual(60.0, p) in 0.0..1.0)
        assertTrue(kernel.actionAt(60.0, p) >= 0.0)
        assertEquals(0.0, kernel.iobResidual(4.0 * 60.0, p), 1e-9)
    }
}
