package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HypoThresholdMathTest : TestBase() {

    @Test
    fun computeHypoThreshold_at_min_bg_90_is_65() {
        assertThat(HypoThresholdMath.computeHypoThreshold(90.0, null)).isEqualTo(65.0)
    }

    @Test
    fun lgs_threshold_wins_when_it_is_higher() {
        assertThat(HypoThresholdMath.computeHypoThreshold(90.0, 80)).isEqualTo(80.0)
    }
}
