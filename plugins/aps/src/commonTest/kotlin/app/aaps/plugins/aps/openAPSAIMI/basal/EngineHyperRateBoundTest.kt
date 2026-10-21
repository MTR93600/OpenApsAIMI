package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Engine rates from the support package of 2026-10-20 (profile basal 0.6 U/h).
 *
 * Source: `dev_OAPSAIMI` @ `bef0366f4c` `EngineHyperRateBoundTest`, same cases in `kotlin.test`.
 */
class EngineHyperRateBoundTest {

    /** 20:43: "Slow rise" asked 199.46 U/h at BG 204 (factor 5). */
    @Test
    fun slowRiseAt199IsReportedButNotChangedWhenDisarmed() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, 199.46, 0.6, 5.0, armed = false)
        assertEquals(3.0, v.boundUph, absoluteTolerance = 1e-9)
        assertTrue(v.wouldBind)
        assertFalse(v.applied)
        assertEquals(199.46, v.rateOutUph)
    }

    @Test
    fun slowRiseAt199IsBoundWhenArmed() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, 199.46, 0.6, 5.0, armed = true)
        assertTrue(v.applied)
        assertEquals(3.0, v.rateOutUph, absoluteTolerance = 1e-9)
    }

    /** 14:47: "Eventual BG>110" asked 27.80 U/h at BG 154.5 (factor 3.725). */
    @Test
    fun eventualBranchUsesASingleGlucoseFactor() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.EVENTUAL_BG_110, 27.80, 0.6, 3.725, armed = true)
        assertEquals(2.235, v.rateOutUph, absoluteTolerance = 1e-9)
    }

    @Test
    fun rateUnderTheBoundIsUntouched() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, 1.0, 0.6, 3.5, armed = true)
        assertFalse(v.wouldBind)
        assertEquals(1.0, v.rateOutUph)
    }

    @Test
    fun badInputsNeverBind() {
        for ((raw, profile, factor) in listOf(Triple(Double.NaN, 0.6, 5.0), Triple(10.0, 0.0, 5.0), Triple(10.0, 0.6, Double.NaN))) {
            val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, raw, profile, factor, armed = true)
            assertFalse(v.wouldBind)
            assertFalse(v.applied)
        }
    }
}
