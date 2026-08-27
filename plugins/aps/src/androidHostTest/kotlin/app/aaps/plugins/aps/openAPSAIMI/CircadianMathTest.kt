package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CircadianMathTest : TestBase() {

    @Test
    fun hourly_at_midnight_is_the_intercept() {
        assertThat(abs(circadianSensitivityHourly(0.0) - CIRCADIAN_INTERCEPT)).isLessThan(1e-12)
    }

    @Test
    fun smb_scale_at_delta_one_rounds_the_intercept() {
        assertThat(circadianSmbScaled(0.0, 1.0f)).isEqualTo(1.39)
    }
}
