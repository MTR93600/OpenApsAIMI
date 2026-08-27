package app.aaps.plugins.aps.openAPSAIMI.carbs

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CarbsAdvisorTest : TestBase() {

    @Test
    fun estimate_returns_zero_when_future_bg_is_at_or_above_target() {
        val grams = CarbsAdvisor.estimateRequiredCarbs(
            bg = 120.0,
            targetBG = 100.0,
            slope = 0.0,
            iob = 0.0,
            csf = 5.0,
            isf = 40.0,
            cob = 0.0
        )
        assertThat(grams).isEqualTo(0)
    }

    @Test
    fun estimate_returns_positive_carbs_when_projected_below_target() {
        val grams = CarbsAdvisor.estimateRequiredCarbs(
            bg = 80.0,
            targetBG = 100.0,
            slope = -1.0,
            iob = 1.0,
            csf = 5.0,
            isf = 40.0,
            cob = 0.0
        )
        assertThat(grams).isGreaterThan(0)
    }
}
