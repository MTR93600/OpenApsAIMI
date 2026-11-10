package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.compose.PkpdCorrectionPrudence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IsfFusionRobustnessTest {

    @Test
    fun `two steps left of center ISF factors do not crash fusion`() {
        val (min, max) = PkpdCorrectionPrudence.factorsForLevel(0.4)
        assertEquals(0.77, min, 0.01)
        assertEquals(1.22, max, 0.01)
        val fusion = IsfFusion(IsfFusionBounds(minFactor = min, maxFactor = max, maxChangePer5Min = 0.4))
        val result = fusion.fused(profileIsf = 50.0, tddIsf = 45.0, pkpdScale = 1.1, nowMs = 0L, authoritative = true, isRising = true)
        assertTrue(result.isFinite())
        assertTrue(result >= 1.0)
    }

    @Test
    fun `inverted ISF bounds are normalized without throw`() {
        val fusion = IsfFusion(IsfFusionBounds(minFactor = 0.95, maxFactor = 0.8, maxChangePer5Min = 0.9))
        val result = fusion.fused(80.0, 60.0, 1.0, nowMs = 0L, authoritative = true)
        assertTrue(result.isFinite())
    }

    @Test
    fun `inverted PKPD bounds normalize for clamp`() {
        val bounds = PkPdBounds(diaMinH = 8.0, diaMaxH = 5.0, peakMinMin = 90.0, peakMinMax = 40.0).normalized()
        assertTrue(bounds.diaMinH <= bounds.diaMaxH)
        assertTrue(bounds.peakMinMin <= bounds.peakMinMax)
        val clamped = PkPdParams(diaHrs = 6.0, peakMin = 55.0)
        val dia = clamped.diaHrs.coerceIn(bounds.diaMinH, bounds.diaMaxH)
        val peak = clamped.peakMin.coerceIn(bounds.peakMinMin, bounds.peakMinMax)
        assertFalse(dia.isNaN())
        assertFalse(peak.isNaN())
    }

    @Test
    fun `sanitize replaces non finite learned state`() {
        val clean = sanitizePkPdParams(PkPdParams(diaHrs = Double.NaN, peakMin = 0.0))
        assertEquals(6.0, clean.diaHrs, 0.0)
        assertEquals(75.0, clean.peakMin, 0.0)
    }
}
