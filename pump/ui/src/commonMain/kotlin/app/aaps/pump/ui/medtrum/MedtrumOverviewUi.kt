package app.aaps.pump.ui.medtrum

import androidx.compose.runtime.Composable
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewScreen
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.StatusBanner
import androidx.compose.ui.tooling.preview.Preview

/**
 * Medtrum pump overview screen, ported to commonMain.
 *
 * Ported from `pump/medtrum/.../compose/MedtrumOverviewScreen.kt`.
 * The Android ViewModel is replaced by hoisted [state]; the pump image
 * (`painterResource`) is replaced by the [pumpImage] slot so each platform
 * provides its own image loading.
 */
@Composable
fun MedtrumOverviewScreen(
    state: PumpOverviewUiState,
    pumpImage: @Composable () -> Unit
) {
    PumpOverviewScreen(
        state = state,
        customContent = pumpImage
    )
}

@Preview
@Composable
private fun MedtrumOverviewPreview() {
    MedtrumOverviewScreen(
        state = PumpOverviewUiState(
            statusBanner = StatusBanner(text = "Connected"),
            infoRows = listOf(
                PumpInfoRow(label = "Reservoir", value = "180 U"),
                PumpInfoRow(label = "Patch", value = "Active")
            )
        ),
        pumpImage = {}
    )
}
