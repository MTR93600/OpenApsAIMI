package app.aaps.plugins.aps.openAPSAIMI.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Cases from the support package of 2026-10-20 (ISF = stack_drop_mgdl / iob_u from the export).
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
        assertThat(v.wouldCap).isTrue()
        assertThat(v.applied).isTrue()
        assertThat(v.rateOutUph).isEqualTo(0.60)
        assertThat(v.capUph).isEqualTo(0.60)
        assertThat(v.stackDropMgdl!!).isWithin(0.1).of(169.0)
        assertThat(v.bgMinusFloorMgdl!!).isWithin(1e-9).of(78.0)
    }

    /** 17:47: forced early TBR at 7.0 U/h, stack 59 ≥ 53. */
    @Test
    fun forcedEarlyTbrWithStackIsCapped() {
        val v = eval(rate = 7.0, bg = 123.0, iob = 1.51, isf = 39.1)
        assertThat(v.rateOutUph).isEqualTo(0.60)
    }

    /** 09:57: stack 66 < 83, the rise keeps its rate. */
    @Test
    fun stackBelowFloorKeepsTheRate() {
        val v = eval(rate = 6.80, bg = 153.0, iob = 2.13, isf = 31.0)
        assertThat(v.wouldCap).isFalse()
        assertThat(v.rateOutUph).isEqualTo(6.80)
    }

    /** 13:07: declared FCL meal, never touched. */
    @Test
    fun declaredMealIsExempt() {
        val v = eval(rate = 5.50, bg = 206.0, iob = 9.89, isf = 40.2, declaredMeal = true)
        assertThat(v.wouldCap).isFalse()
        assertThat(v.declaredMealExempt).isTrue()
        assertThat(v.rateOutUph).isEqualTo(5.50)
    }

    @Test
    fun neverRaisesARateBelowProfile() {
        val v = eval(rate = 0.30, bg = 140.0, iob = 5.0, isf = 50.0)
        assertThat(v.wouldCap).isFalse()
        assertThat(v.rateOutUph).isEqualTo(0.30)
    }

    /** Shadow mode: the verdict is computed and exported, the rate is not changed. */
    @Test
    fun disarmedOnlyReportsWouldCap() {
        val v = eval(armed = false)
        assertThat(v.wouldCap).isTrue()
        assertThat(v.applied).isFalse()
        assertThat(v.rateOutUph).isEqualTo(4.58)
    }

    @Test
    fun missingOrBadIsfNeverCaps() {
        for (isf in listOf(null, Double.NaN, 0.0)) {
            val v = eval(isf = isf)
            assertThat(v.wouldCap).isFalse()
            assertThat(v.rateOutUph).isEqualTo(4.58)
        }
    }

    @Test
    fun negativeIobCountsAsZero() {
        val v = eval(rate = 2.0, bg = 75.0, iob = -0.5, isf = 50.0)
        assertThat(v.stackDropMgdl).isEqualTo(0.0)
        assertThat(v.wouldCap).isFalse()
    }

    @Test
    fun glucoseUnderTheFloorCaps() {
        val v = eval(rate = 1.0, bg = 68.0, iob = 0.1, isf = 50.0)
        assertThat(v.rateOutUph).isEqualTo(0.60)
    }

    @Test
    fun nonFiniteRatePassesThrough() {
        val v = eval(rate = Double.NaN)
        assertThat(v.applied).isFalse()
        assertThat(v.rateOutUph.isNaN()).isTrue()
    }
}
