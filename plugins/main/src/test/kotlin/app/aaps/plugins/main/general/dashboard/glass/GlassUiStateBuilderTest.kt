package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [buildGlassUiState] returns an early all-defaults [GlassUiState] when [StatusCardState] is
 * null (see its `if (status == null) return GlassUiState()` guard) — that guard is not touched by
 * this test. So a minimal, non-null [StatusCardState] is passed here to exercise the
 * lastBolusText/lastCarbsText "most recent valid" reduction instead.
 */
class GlassUiStateBuilderTest {

    private fun minimalStatus() = StatusCardState(
        glucoseText = "--",
        glucoseColor = 0,
        trendArrowRes = null,
        trendDescription = "",
        deltaText = "",
        iobText = "",
        cobText = "",
        loopStatusText = "",
        loopIsRunning = false,
        timeAgo = "",
        timeAgoDescription = "",
        isGlucoseActual = false,
        contentDescription = "",
    )

    private fun treatmentData(boluses: List<BolusGraphPoint> = emptyList(), carbs: List<CarbsGraphPoint> = emptyList()) =
        TreatmentGraphData(boluses = boluses, carbs = carbs, extendedBoluses = emptyList(), therapyEvents = emptyList())

    @Test
    fun `most recent valid bolus label is used, invalid and older ones are ignored`() {
        val data = treatmentData(
            boluses = listOf(
                BolusGraphPoint(timestamp = 1000L, amount = 1.0, bolusType = BolusType.NORMAL, isValid = true, label = "1.00 U"),
                BolusGraphPoint(timestamp = 3000L, amount = 2.0, bolusType = BolusType.NORMAL, isValid = false, label = "2.00 U (invalid)"),
                BolusGraphPoint(timestamp = 2000L, amount = 1.5, bolusType = BolusType.SMB, isValid = true, label = "1.50 U"),
            )
        )
        val state = buildGlassUiState(status = minimalStatus(), lights = null, treatmentData = data)
        assertThat(state.lastBolusText).isEqualTo("1.50 U")
    }

    @Test
    fun `no valid bolus or carbs falls back to placeholder`() {
        val state = buildGlassUiState(status = minimalStatus(), lights = null, treatmentData = treatmentData())
        assertThat(state.lastBolusText).isEqualTo("--")
        assertThat(state.lastCarbsText).isEqualTo("--")
    }
}
