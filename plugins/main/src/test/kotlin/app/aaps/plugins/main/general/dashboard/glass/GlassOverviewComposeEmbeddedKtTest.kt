package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression tests for [buildGlassUiState] — the mapper from the shared [StatusCardState] /
 * status-lights state into the Glass skin's [GlassUiState]. Covers the fixes from the final
 * whole-branch review: unit label, temp-target flag, and loop-running flag must come from real
 * dashboard state, not be hardcoded or re-derived from an unrelated field.
 */
class GlassOverviewComposeEmbeddedKtTest {

    private fun statusCardState(
        targetText: String? = "--",
        isTempTargetActive: Boolean = false,
        unitText: String = "mg/dL",
        loopIsRunning: Boolean = true,
    ): StatusCardState =
        StatusCardState(
            glucoseText = "120",
            glucoseColor = 0xFF000000.toInt(),
            trendArrowRes = null,
            trendDescription = "",
            deltaText = "+1",
            iobText = "1.0 U",
            cobText = "0 g",
            loopStatusText = "Loop",
            loopIsRunning = loopIsRunning,
            timeAgo = "1 min ago",
            timeAgoDescription = "1 min ago",
            isGlucoseActual = true,
            contentDescription = "",
            targetText = targetText,
            isTempTargetActive = isTempTargetActive,
            unitText = unitText,
        )

    private val emptyTreatmentData = TreatmentGraphData(
        boluses = emptyList(),
        carbs = emptyList(),
        extendedBoluses = emptyList(),
        therapyEvents = emptyList(),
    )

    @Test
    fun `null status maps to all-defaults GlassUiState`() {
        val result = buildGlassUiState(status = null, lights = null, treatmentData = emptyTreatmentData)

        assertThat(result).isEqualTo(GlassUiState())
    }

    @Test
    fun `active temp target maps to isTempTargetActive true`() {
        val result = buildGlassUiState(status = statusCardState(isTempTargetActive = true), lights = null, treatmentData = emptyTreatmentData)

        assertThat(result.isTempTargetActive).isTrue()
    }

    @Test
    fun `no active temp target maps to isTempTargetActive false`() {
        val result = buildGlassUiState(status = statusCardState(isTempTargetActive = false), lights = null, treatmentData = emptyTreatmentData)

        assertThat(result.isTempTargetActive).isFalse()
    }

    @Test
    fun `unit text is read from StatusCardState, not hardcoded`() {
        val result = buildGlassUiState(status = statusCardState(unitText = "mmol/L"), lights = null, treatmentData = emptyTreatmentData)

        assertThat(result.unit).isEqualTo("mmol/L")
    }

    @Test
    fun `loop not running maps to loopIsRunning false`() {
        val result = buildGlassUiState(status = statusCardState(loopIsRunning = false), lights = null, treatmentData = emptyTreatmentData)

        assertThat(result.loopIsRunning).isFalse()
    }
}
