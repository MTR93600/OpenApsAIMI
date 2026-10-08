package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HyperClampTubeBoundTest {

    private fun eval(
        minPred: Double = 80.0,
        bg: Double = 174.0,
        iob: Double = 1.0,
        tubeIsf: Double = 25.0,
        doseIsf: Double? = 25.0,
        floor: Double = 76.0,
        clampPossible: Boolean = true,
        armed: Boolean = true,
    ) = HyperClampTubeBound.evaluate(minPred, bg, iob, tubeIsf, doseIsf, floor, clampPossible, armed)

    /** Field case shape (2026-10-05 15:36): rising meal, little insulin on board → real room. */
    @Test
    fun lowStackOnARiseGivesTheTubeRealRoom() {
        val v = eval(bg = 174.0, iob = 2.0, tubeIsf = 25.0, doseIsf = 25.0)
        assertThat(v.clampDetected).isTrue()
        assertThat(v.applied).isTrue()
        assertThat(v.minPredForTubeMgdl).isWithin(1e-9).of(124.0)
        assertThat(v.stackCanBreachFloor).isFalse()
    }

    /** Field case (2026-10-06 14:54): BG 160.4 with 10.8 U on board → the bound sits under the floor. */
    @Test
    fun largeStackIsVetoedInsteadOfReleasedAt160() {
        val v = eval(bg = 160.4, iob = 10.8, tubeIsf = 42.0, doseIsf = 42.0, floor = 70.0)
        assertThat(v.applied).isTrue()
        assertThat(v.stackCanBreachFloor).isTrue()
        assertThat(v.minPredForTubeMgdl).isLessThan(70.0)
    }

    /** Sensitivity comes back as glucose falls: the larger ISF is the cautious one. */
    @Test
    fun usesTheLargerIsfAndExportsTheDoseIsfVariant() {
        val v = eval(bg = 174.0, iob = 1.5, tubeIsf = 60.0, doseIsf = 25.0)
        assertThat(v.isfUsedMgdlPerU).isEqualTo(60.0)
        assertThat(v.minPredForTubeMgdl).isWithin(1e-9).of(84.0)
        assertThat(v.physicalMinDoseIsfMgdl!!).isWithin(1e-9).of(136.5)
    }

    @Test
    fun belowHyperLevelOrNotTheClampValueIsLeftAlone() {
        assertThat(eval(bg = 159.9).clampDetected).isFalse()
        assertThat(eval(minPred = 95.0).clampDetected).isFalse()
        assertThat(eval(minPred = 39.0).minPredForTubeMgdl).isEqualTo(39.0)
        assertThat(eval(clampPossible = false).clampDetected).isFalse()
    }

    /** Disarmed: the bound is computed and exported, the tube keeps the old value. */
    @Test
    fun disarmedOnlyReports() {
        val v = eval(iob = 2.0, armed = false)
        assertThat(v.clampDetected).isTrue()
        assertThat(v.applied).isFalse()
        assertThat(v.minPredForTubeMgdl).isEqualTo(80.0)
        assertThat(v.physicalMinMgdl).isNotNull()
    }

    /** Fail closed: no usable IOB or ISF → keep the clamp value the tube used before this rule. */
    @Test
    fun missingInputsKeepTheOldValue() {
        val noIsf = eval(tubeIsf = Double.NaN, doseIsf = null)
        assertThat(noIsf.clampDetected).isTrue()
        assertThat(noIsf.applied).isFalse()
        assertThat(noIsf.minPredForTubeMgdl).isEqualTo(80.0)
        assertThat(eval(iob = Double.NaN).applied).isFalse()
    }

    /** A negative IOB (net low temp) must not raise the bound above glucose itself. */
    @Test
    fun negativeIobCountsAsZero() {
        assertThat(eval(bg = 200.0, iob = -0.8).minPredForTubeMgdl).isEqualTo(200.0)
    }
}
