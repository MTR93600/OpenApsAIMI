package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cases from the support package of 2026-10-20 (ISF = stack_drop_mgdl / iob_u from the export).
 *
 * Source: `dev_OAPSAIMI` @ `bef0366f4c` `BasalStackTerminalCapTest`, same cases in `kotlin.test`.
 */
class BasalStackTerminalCapTest {

    private fun eval(
        rate: Double = 4.58,
        profile: Double = 0.60,
        bg: Double = 148.0,
        iob: Double = 3.55,
        isf: Double? = 47.6,
        declaredMeal: Boolean = false,
        armed: Boolean = true,
    ) = BasalStackTerminalCap.evaluate(rate, profile, bg, iob, isf, declaredMeal, armed)

    /** 23:27: inter-wave overlay at 4.58 U/h, stack 169 ≥ 78. */
    @Test
    fun interWaveOverlayWithStackIsCappedToProfile() {
        val v = eval()
        assertTrue(v.wouldCap)
        assertTrue(v.applied)
        assertEquals(0.60, v.rateOutUph)
        assertEquals(0.60, v.capUph)
        assertEquals(169.0, v.stackDropMgdl!!, absoluteTolerance = 0.1)
        assertEquals(78.0, v.bgMinusFloorMgdl!!, absoluteTolerance = 1e-9)
    }

    /** 17:47: forced early TBR at 7.0 U/h, stack 59 ≥ 53. */
    @Test
    fun forcedEarlyTbrWithStackIsCapped() {
        val v = eval(rate = 7.0, bg = 123.0, iob = 1.51, isf = 39.1)
        assertEquals(0.60, v.rateOutUph)
    }

    /** 09:57: stack 66 < 83, the rise keeps its rate. */
    @Test
    fun stackBelowFloorKeepsTheRate() {
        val v = eval(rate = 6.80, bg = 153.0, iob = 2.13, isf = 31.0)
        assertFalse(v.wouldCap)
        assertEquals(6.80, v.rateOutUph)
    }

    /** 13:07: declared FCL meal, never touched. */
    @Test
    fun declaredMealIsExempt() {
        val v = eval(rate = 5.50, bg = 206.0, iob = 9.89, isf = 40.2, declaredMeal = true)
        assertFalse(v.wouldCap)
        assertTrue(v.declaredMealExempt)
        assertEquals(5.50, v.rateOutUph)
    }

    @Test
    fun neverRaisesARateBelowProfile() {
        val v = eval(rate = 0.30, bg = 140.0, iob = 5.0, isf = 50.0)
        assertFalse(v.wouldCap)
        assertEquals(0.30, v.rateOutUph)
    }

    /** Shadow mode: the verdict is computed and exported, the rate is not changed. */
    @Test
    fun disarmedOnlyReportsWouldCap() {
        val v = eval(armed = false)
        assertTrue(v.wouldCap)
        assertFalse(v.applied)
        assertEquals(4.58, v.rateOutUph)
    }

    @Test
    fun missingOrBadIsfNeverCaps() {
        for (isf in listOf(null, Double.NaN, 0.0)) {
            val v = eval(isf = isf)
            assertFalse(v.wouldCap)
            assertEquals(4.58, v.rateOutUph)
        }
    }

    @Test
    fun negativeIobCountsAsZero() {
        val v = eval(rate = 2.0, bg = 75.0, iob = -0.5, isf = 50.0)
        assertEquals(0.0, v.stackDropMgdl)
        assertFalse(v.wouldCap)
    }

    @Test
    fun glucoseUnderTheFloorCaps() {
        val v = eval(rate = 1.0, bg = 68.0, iob = 0.1, isf = 50.0)
        assertEquals(0.60, v.rateOutUph)
    }

    @Test
    fun nonFiniteRatePassesThrough() {
        val v = eval(rate = Double.NaN)
        assertFalse(v.applied)
        assertTrue(v.rateOutUph.isNaN())
    }
}
