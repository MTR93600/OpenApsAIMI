package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicBasalStackCapTest {

    private fun eval(
        multiplier: Double = 3.0,
        bg: Double = 136.0,
        iob: Double = 3.0,
        isf: Double? = 40.0,
        declaredMeal: Boolean = false,
        armed: Boolean = true,
    ) = DynamicBasalStackCap.evaluate(multiplier, bg, iob, isf, declaredMeal, armed)

    /** Field case shape (2026-10-17): undeclared rise to 136, 3 U on board, ISF 40 -> 120 >= 66. */
    @Test
    fun stackThatCanReach70HoldsTheMultiplierAtOne() {
        val v = eval()
        assertTrue(v.wouldCap)
        assertTrue(v.applied)
        assertEquals(1.0, v.multiplierOut)
        assertEquals(120.0, v.stackDropMgdl!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun smallStackKeepsTheMultiplier() {
        val v = eval(iob = 1.0)
        assertFalse(v.wouldCap)
        assertEquals(3.0, v.multiplierOut)
    }

    /** A declared meal is never touched, whatever the stack. */
    @Test
    fun declaredMealIsExempt() {
        val v = eval(iob = 9.6, declaredMeal = true)
        assertFalse(v.wouldCap)
        assertTrue(v.declaredMealExempt)
        assertEquals(3.0, v.multiplierOut)
    }

    /** It only stops raising: a brake or a neutral multiplier is never changed. */
    @Test
    fun multiplierAtOrBelowOneIsNeverChanged() {
        assertEquals(0.4, eval(multiplier = 0.4, iob = 9.0).multiplierOut)
        assertFalse(eval(multiplier = 1.0, iob = 9.0).wouldCap)
    }

    @Test
    fun disarmedOnlyReports() {
        val v = eval(armed = false)
        assertTrue(v.wouldCap)
        assertFalse(v.applied)
        assertEquals(3.0, v.multiplierOut)
    }

    @Test
    fun missingIsfOrIobDisablesTheCap() {
        assertFalse(eval(isf = null).wouldCap)
        assertFalse(eval(iob = Double.NaN).wouldCap)
    }

    @Test
    fun declaredMealWindowIsThreeHoursOrFourForHighCarb() {
        assertTrue(DynamicBasalStackCap.declaredMealActive(true, null, false))
        assertTrue(DynamicBasalStackCap.declaredMealActive(false, 179L, false))
        assertFalse(DynamicBasalStackCap.declaredMealActive(false, 180L, false))
        assertTrue(DynamicBasalStackCap.declaredMealActive(false, 200L, true))
        assertFalse(DynamicBasalStackCap.declaredMealActive(false, null, false))
    }
}
