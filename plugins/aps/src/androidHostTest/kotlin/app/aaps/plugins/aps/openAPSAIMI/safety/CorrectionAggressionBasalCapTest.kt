package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CorrectionAggressionBasalCapTest : TestBase() {

    @Test
    fun apply_does_not_cap_when_gate_is_missing() {
        val result = CorrectionAggressionBasalCap.apply(
            requestedRateUph = 4.0,
            profileBasalUph = 1.0,
            gate = null,
        )
        assertThat(result.wasCapped).isFalse()
        assertThat(result.cappedRateUph).isEqualTo(4.0)
    }

    @Test
    fun formatLogLine_uses_dot_decimals() {
        val result = CorrectionAggressionBasalCap.Result(
            cappedRateUph = 1.5,
            wasCapped = true,
            maxAllowedUph = 1.5,
        )
        val line = CorrectionAggressionBasalCap.formatLogLine(
            source = "test",
            requestedUph = 2.0,
            result = result,
            tier = null,
        )
        assertThat(line).contains("2.00→1.50")
    }
}
