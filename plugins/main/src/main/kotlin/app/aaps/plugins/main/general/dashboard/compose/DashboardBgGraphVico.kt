package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState
import app.aaps.ui.compose.overview.graphs.BgGraphCompose
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState

/**
 * Dashboard BG chart using the same Vico stack as Overview ([BgGraphCompose] + [GraphViewModel]).
 *
 * Share [scrollState] / [zoomState] with [app.aaps.ui.compose.overview.graphs.SecondaryGraphCompose] (IOB).
 * Activity is **not** shown on the dashboard (no strip, no ACTIVITY overlay) to avoid a busy layout;
 * Overview settings are unchanged — we only filter here.
 *
 * SMB/TBR on the BG layer come from [graphRenderInput] (shell); IOB row uses [OverviewDataCache] treatment flow.
 */
@Composable
internal fun DashboardBgGraphVico(
    graphViewModel: GraphViewModel,
    graphRenderInput: DashboardEmbeddedComposeState.GraphRenderInput,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val nowTimestamp by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
    val savedConfig by graphViewModel.graphConfigFlow.collectAsStateWithLifecycle()
    val bgOverlays = remember(savedConfig.bgOverlays) {
        buildList {
            addAll(savedConfig.bgOverlays.filter { it != SeriesType.ACTIVITY })
            if (SeriesType.PREDICTIONS !in this) {
                add(SeriesType.PREDICTIONS)
            }
        }
    }

    Box(modifier = modifier) {
        BgGraphCompose(
            viewModel = graphViewModel,
            bgOverlays = bgOverlays,
            scrollState = scrollState,
            zoomState = zoomState,
            derivedTimeRange = derivedTimeRange,
            nowTimestamp = nowTimestamp,
            useMaterial3DashboardStyle = false,
            dashboardSmbMarkers = graphRenderInput.smbMarkers,
            dashboardTbrSegments = graphRenderInput.tbrSegments,
            dashboardTbrMarkerEpochMs = graphRenderInput.tbrMarkerEpochMs,
            lockStartAxisYFromZero = true,
            dashboardSoftTherapyVisuals = true,
            dashboardScenarioProjectionVisuals = true,
            dashboardSplitActivityToStrip = false,
            onDashboardSmbMarkerTap =
                if (graphRenderInput.smbMarkers.isNotEmpty()) { marker ->
                    ToastUtils.infoToast(
                        context,
                        context.getString(R.string.dashboard_graph_smb_toast, marker.amountLabel),
                    )
                } else {
                    null
                },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
