package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DynamicSensitivityPolicyTest : TestBase() {

    @Test
    fun factorFor_is_neutral_when_any_input_is_missing() {
        assertThat(DynamicSensitivityPolicy.factorFor(null, 0.0, 120.0)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(0.0, null, 120.0)).isEqualTo(1.0)
        assertThat(DynamicSensitivityPolicy.factorFor(0.0, 0.0, null)).isEqualTo(1.0)
    }

    @Test
    fun factorFor_at_bg_240_matches_the_2026_corpus_calibration() {
        val factor = DynamicSensitivityPolicy.factorFor(delta = 0.0, predicted = 0.0, bgMgdl = 240.0)
        assertThat(factor).isWithin(0.001).of(0.783)
    }

    @Test
    fun floorAgainstProfile_raises_crushed_sensitivity_to_half_profile() {
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(10.0, 30.0)).isEqualTo(15.0)
    }

    @Test
    fun floorAgainstProfile_does_not_lower_a_higher_commanded_value() {
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(20.0, 30.0)).isEqualTo(20.0)
    }

    @Test
    fun floorAgainstProfile_fails_open_when_profile_is_missing() {
        assertThat(DynamicSensitivityPolicy.floorAgainstProfile(10.0, null)).isEqualTo(10.0)
    }
}
