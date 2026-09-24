package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrefPredictionReasonSuffixTest {

    @Test
    fun build_contains_oref_tokens_and_min_across_series() {
        val rT = RT(
            runningDynamicIsf = false,
            predBGs = Predictions(
                IOB = listOf(100, 90, 85),
                COB = listOf(100, 95, 88),
                UAM = listOf(100, 92, 87),
                ZT = listOf(100, 91, 86),
            ),
        )
        val suffix = OrefPredictionReasonSuffix.build(rT) { v -> "%.0f".format(v) }
        assertThat(suffix).startsWith(", minPredBG")
        assertThat(suffix).contains("minPredBG 85")
        assertThat(suffix).contains("minGuardBG 85")
        assertThat(suffix).contains("IOBpredBG 85")
        assertThat(suffix).contains("UAMpredBG 87")
    }

    @Test
    fun build_empty_when_no_predictions() {
        val rT = RT(runningDynamicIsf = false, predBGs = null)
        assertThat(OrefPredictionReasonSuffix.build(rT) { it.toString() }).isEmpty()
    }
}
