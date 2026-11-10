package app.aaps.plugins.aps.openAPSAIMI.ISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DynamicSensitivityPolicyTest {

    // ---- the regression that mattered ------------------------------------------------------------

    /**
     * The rise-rate term is gone.
     *
     * `exp(-0.3 * (combinedDelta - 10))` returned 0.0073 at a combined delta of +26, which is how a
     * commanded sensitivity of 4.54 mg/dL/U was reached at BG 186.6 on 2026-08-14 against a static
     * profile of 30.
     */
    @Test
    fun `a steep rise no longer crushes the sensitivity`() {
        val factor = DynamicSensitivityPolicy.factorFor(delta = 26.4, predicted = 26.4, bgMgdl = 186.6)
        assertThat(factor).isAtLeast(DynamicSensitivityPolicy.HYPER_COMPRESSION_FLOOR)
        // The BG term alone, at BG 186.6: 1 - (76.6/90)*0.15 = 0.8723
        assertThat(factor).isWithin(1e-4).of(0.8723)
    }

    /** The factor can no longer reach zero or go negative, at any glucose. */
    @Test
    fun `the factor is never below the floor however high glucose goes`() {
        for (bg in listOf(200.0, 250.0, 290.0, 297.0, 400.0, 600.0)) {
            val factor = DynamicSensitivityPolicy.factorFor(delta = 5.0, predicted = 5.0, bgMgdl = bg)
            assertThat(factor).isAtLeast(DynamicSensitivityPolicy.HYPER_COMPRESSION_FLOOR)
            assertThat(factor).isGreaterThan(0.0)
        }
    }

    /**
     * The two production ticks that produced a negative multiplier.
     *
     * 2026-08-12, BG 290 rising +4.6 and BG 297 rising +4.0: `isf_dynamic_factor` was exported as -0.00
     * and -0.04. Only the absolute `coerceIn(5.0, 300.0)` kept a negative sensitivity off the pump.
     */
    @Test
    fun `the two measured negative ticks are now bounded`() {
        assertThat(DynamicSensitivityPolicy.factorFor(delta = 4.56, predicted = 4.56, bgMgdl = 290.5))
            .isEqualTo(DynamicSensitivityPolicy.HYPER_COMPRESSION_FLOOR)
        assertThat(DynamicSensitivityPolicy.factorFor(delta = 4.01, predicted = 4.01, bgMgdl = 297.2))
            .isEqualTo(DynamicSensitivityPolicy.HYPER_COMPRESSION_FLOOR)
    }

    // ---- the calibration -------------------------------------------------------------------------

    /**
     * The compression reproduces what 96 clean descents measured: 0.77 relative at a starting BG of
     * about 240, against 1.00 in the 70-140 band.
     */
    @Test
    fun `the compression matches the measured x1_3 across the range`() {
        assertThat(DynamicSensitivityPolicy.factorFor(delta = 2.0, predicted = 2.0, bgMgdl = 110.0)).isWithin(1e-9).of(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(delta = 2.0, predicted = 2.0, bgMgdl = 200.0)).isWithin(1e-4).of(0.85)
        assertThat(DynamicSensitivityPolicy.factorFor(delta = 2.0, predicted = 2.0, bgMgdl = 240.0)).isWithin(1e-3).of(0.783)
    }

    @Test
    fun `at or below the start glucose the factor is neutral`() {
        assertThat(DynamicSensitivityPolicy.factorFor(delta = 0.0, predicted = 0.0, bgMgdl = 110.0)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(delta = 0.0, predicted = 0.0, bgMgdl = 95.0)).isEqualTo(1.0)
    }

    /** Monotone in glucose: higher glucose never yields a higher factor. */
    @Test
    fun `the factor is monotone in glucose on a rise`() {
        var previous = Double.MAX_VALUE
        for (bg in 110..400 step 10) {
            val factor = DynamicSensitivityPolicy.factorFor(delta = 3.0, predicted = 3.0, bgMgdl = bg.toDouble())
            assertThat(factor).isAtMost(previous + 1e-12)
            previous = factor
        }
    }

    // ---- the falling arm, deliberately unchanged --------------------------------------------------

    /**
     * Untouched on purpose. It raises the sensitivity on a fall, so the loop predicts a larger effect
     * from the insulin on board and doses less. Lowering the cap would permit more insulin on a fall.
     */
    @Test
    fun `the falling arm keeps its previous behaviour and cap`() {
        assertThat(DynamicSensitivityPolicy.factorFor(delta = -2.0, predicted = -2.0, bgMgdl = 150.0))
            .isWithin(1e-6).of(Math.exp(0.15 * 2.0))
        assertThat(DynamicSensitivityPolicy.factorFor(delta = -30.0, predicted = -30.0, bgMgdl = 150.0))
            .isEqualTo(DynamicSensitivityPolicy.FALLING_FACTOR_CAP)
    }

    /** A fall at high glucose still takes the falling arm, not the compression. */
    @Test
    fun `a fall at high glucose takes the falling arm`() {
        assertThat(DynamicSensitivityPolicy.factorFor(delta = -8.0, predicted = -8.0, bgMgdl = 250.0))
            .isGreaterThan(1.0)
    }

    // ---- fail-open -------------------------------------------------------------------------------

    @Test
    fun `null or non finite inputs are neutral`() {
        assertThat(DynamicSensitivityPolicy.factorFor(null, 1.0, 150.0)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(1.0, null, 150.0)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(1.0, 1.0, null)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(Double.NaN, 1.0, 150.0)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(1.0, Double.POSITIVE_INFINITY, 150.0)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(1.0, 1.0, Double.NaN)).isEqualTo(1.0)
    }

    // ---- the profile-relative lower bound --------------------------------------------------------

    @Test
    fun `the profile floor raises a crushed sensitivity to half the profile`() {
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(commandedMgdlPerU = 4.54, profileIsfMgdlPerU = 30.0))
            .isWithin(1e-9).of(15.0)
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(commandedMgdlPerU = 4.85, profileIsfMgdlPerU = 70.0))
            .isWithin(1e-9).of(35.0)
    }

    /** It can only ever raise — there is no path by which this bound permits more insulin. */
    @Test
    fun `the profile floor never lowers the commanded sensitivity`() {
        for (commanded in listOf(0.1, 4.5, 15.0, 20.0, 30.0, 65.5, 300.0)) {
            val out = DynamicSensitivityPolicy.floorAgainstProfile(commanded, 30.0)
            assertThat(out).isAtLeast(commanded)
        }
    }

    /** No upper bound in this change: a sensitivity above profile is left alone. */
    @Test
    fun `the profile floor leaves a high sensitivity untouched`() {
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(commandedMgdlPerU = 65.2, profileIsfMgdlPerU = 30.0))
            .isEqualTo(65.2)
    }

    @Test
    fun `the profile floor fails open on a bad profile read`() {
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(4.54, null)).isEqualTo(4.54)
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(4.54, 0.0)).isEqualTo(4.54)
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(4.54, -30.0)).isEqualTo(4.54)
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(4.54, Double.NaN)).isEqualTo(4.54)
    }

    @Test
    fun `the profile floor passes a non finite commanded value through unchanged`() {
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(Double.NaN, 30.0)).isNaN()
    }
}
