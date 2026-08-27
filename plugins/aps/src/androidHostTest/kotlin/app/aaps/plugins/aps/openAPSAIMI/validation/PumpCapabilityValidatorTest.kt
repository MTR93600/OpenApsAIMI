package app.aaps.plugins.aps.openAPSAIMI.validation

import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PumpCapabilityValidatorTest : TestBase() {

    private val validator = PumpCapabilityValidator()
    private val caps = PumpCaps(
        basalStep = 0.05,
        bolusStep = 0.05,
        minDurationMin = 30,
        maxBasal = 2.0,
        maxSmb = 1.0
    )

    @Test
    fun validate_basal_clamps_to_max_and_step() {
        val rate = validator.validateBasal(3.13, caps)
        assertThat(rate).isEqualTo(2.0)
    }

    @Test
    fun validate_basal_does_not_go_negative() {
        val rate = validator.validateBasal(-0.2, caps)
        assertThat(rate).isEqualTo(0.0)
    }

    @Test
    fun duration_must_meet_pump_minimum() {
        assertThat(validator.isValidDuration(15, caps)).isFalse()
        assertThat(validator.isValidDuration(30, caps)).isTrue()
    }
}
