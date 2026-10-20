package app.aaps.plugins.aps.openAPSAIMI.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Engine rates from the support package of 2026-10-20 (profile basal 0.6 U/h). */
class EngineHyperRateBoundTest {

    /** 20:43: "Slow rise" asked 199.46 U/h at BG 204 (factor 5). */
    @Test
    fun slowRiseAt199IsReportedButNotChangedWhenDisarmed() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, 199.46, 0.6, 5.0, armed = false)
        assertThat(v.boundUph).isWithin(1e-9).of(3.0)
        assertThat(v.wouldBind).isTrue()
        assertThat(v.applied).isFalse()
        assertThat(v.rateOutUph).isEqualTo(199.46)
    }

    @Test
    fun slowRiseAt199IsBoundWhenArmed() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, 199.46, 0.6, 5.0, armed = true)
        assertThat(v.applied).isTrue()
        assertThat(v.rateOutUph).isWithin(1e-9).of(3.0)
    }

    /** 14:47: "Eventual BG>110" asked 27.80 U/h at BG 154.5 (factor 3.725). */
    @Test
    fun eventualBranchUsesASingleGlucoseFactor() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.EVENTUAL_BG_110, 27.80, 0.6, 3.725, armed = true)
        assertThat(v.rateOutUph).isWithin(1e-9).of(2.235)
    }

    @Test
    fun rateUnderTheBoundIsUntouched() {
        val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, 1.0, 0.6, 3.5, armed = true)
        assertThat(v.wouldBind).isFalse()
        assertThat(v.rateOutUph).isEqualTo(1.0)
    }

    @Test
    fun badInputsNeverBind() {
        for ((raw, profile, factor) in listOf(Triple(Double.NaN, 0.6, 5.0), Triple(10.0, 0.0, 5.0), Triple(10.0, 0.6, Double.NaN))) {
            val v = EngineHyperRateBound.evaluate(EngineHyperRateBound.Branch.SLOW_RISE, raw, profile, factor, armed = true)
            assertThat(v.wouldBind).isFalse()
            assertThat(v.applied).isFalse()
        }
    }
}
