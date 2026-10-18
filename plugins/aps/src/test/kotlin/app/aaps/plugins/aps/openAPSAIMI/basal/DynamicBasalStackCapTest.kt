package app.aaps.plugins.aps.openAPSAIMI.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DynamicBasalStackCapTest {

    private fun eval(
        multiplier: Double = 3.0,
        bg: Double = 136.0,
        iob: Double = 3.0,
        isf: Double? = 40.0,
        declaredMeal: Boolean = false,
        armed: Boolean = true,
    ) = DynamicBasalStackCap.evaluate(multiplier, bg, iob, isf, declaredMeal, armed)

    /** Field case shape (2026-10-17): undeclared rise to 136, 3 U on board, ISF 40 → 120 ≥ 66. */
    @Test
    fun stackThatCanReach70HoldsTheMultiplierAtOne() {
        val v = eval()
        assertThat(v.wouldCap).isTrue()
        assertThat(v.applied).isTrue()
        assertThat(v.multiplierOut).isEqualTo(1.0)
        assertThat(v.stackDropMgdl).isWithin(1e-9).of(120.0)
    }

    @Test
    fun smallStackKeepsTheMultiplier() {
        val v = eval(iob = 1.0)
        assertThat(v.wouldCap).isFalse()
        assertThat(v.multiplierOut).isEqualTo(3.0)
    }

    /** A declared meal is never touched, whatever the stack. */
    @Test
    fun declaredMealIsExempt() {
        val v = eval(iob = 9.6, declaredMeal = true)
        assertThat(v.wouldCap).isFalse()
        assertThat(v.declaredMealExempt).isTrue()
        assertThat(v.multiplierOut).isEqualTo(3.0)
    }

    /** It only stops raising: a brake or a neutral multiplier is never changed. */
    @Test
    fun multiplierAtOrBelowOneIsNeverChanged() {
        assertThat(eval(multiplier = 0.4, iob = 9.0).multiplierOut).isEqualTo(0.4)
        assertThat(eval(multiplier = 1.0, iob = 9.0).wouldCap).isFalse()
    }

    @Test
    fun disarmedOnlyReports() {
        val v = eval(armed = false)
        assertThat(v.wouldCap).isTrue()
        assertThat(v.applied).isFalse()
        assertThat(v.multiplierOut).isEqualTo(3.0)
    }

    @Test
    fun missingIsfOrIobDisablesTheCap() {
        assertThat(eval(isf = null).wouldCap).isFalse()
        assertThat(eval(iob = Double.NaN).wouldCap).isFalse()
    }

    @Test
    fun declaredMealWindowIsThreeHoursOrFourForHighCarb() {
        assertThat(DynamicBasalStackCap.declaredMealActive(true, null, false)).isTrue()
        assertThat(DynamicBasalStackCap.declaredMealActive(false, 179L, false)).isTrue()
        assertThat(DynamicBasalStackCap.declaredMealActive(false, 180L, false)).isFalse()
        assertThat(DynamicBasalStackCap.declaredMealActive(false, 200L, true)).isTrue()
        assertThat(DynamicBasalStackCap.declaredMealActive(false, null, false)).isFalse()
    }
}
