package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Dashboard BG chart in commonMain.
 *
 * Ported from `plugins/main` `DashboardBgGraphVico`, which wrapped the Android
 * Vico stack (`BgGraphCompose` + `GraphViewModel` + `VicoScrollState`/
 * `VicoZoomState`). Vico is Android-only, so this port renders with the
 * commonMain Canvas [DashboardGraphComposeRenderer] instead, driven by the
 * hoisted [DashboardGraphRenderInput].
 *
 * Activity is not shown on the dashboard (no strip, no overlay) to avoid a
 * busy layout — same as the original.
 */
@Composable
fun DashboardBgGraph(
    renderInput: DashboardGraphRenderInput,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        DashboardGraphComposeRenderer(
            renderInput = renderInput,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
