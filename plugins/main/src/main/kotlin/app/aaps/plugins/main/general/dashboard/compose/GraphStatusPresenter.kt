package app.aaps.plugins.main.general.dashboard.compose

import androidx.annotation.StringRes
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState

internal data class GraphStatusUi(
    val summaryRangeHours: Int,
    @StringRes val dataStateRes: Int,
    @StringRes val followStateRes: Int,
    @StringRes val freshnessMessageRes: Int,
    val freshnessMinutes: Int?,
    val freshnessLevel: GraphFreshnessLevel,
)

internal object GraphStatusPresenter {
    fun present(
        renderInput: DashboardEmbeddedComposeState.GraphRenderInput,
        freshnessConfig: DashboardEmbeddedComposeState.GraphFreshnessConfig,
        nowEpochMs: Long,
    ): GraphStatusUi {
        val freshness = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = renderInput.lastRefreshEpochMs,
            nowEpochMs = nowEpochMs,
            warningThresholdMinutes = freshnessConfig.warningThresholdMinutes,
            staleThresholdMinutes = freshnessConfig.staleThresholdMinutes,
        )
        return GraphStatusUi(
            summaryRangeHours = renderInput.rangeHours,
            dataStateRes = if (renderInput.hasBgData) R.string.graph_live else R.string.graph_no_data,
            followStateRes = if (renderInput.followLive && !renderInput.graphPanActive) {
                R.string.graph_following
            } else {
                R.string.graph_fixed
            },
            freshnessMessageRes = freshness.messageRes,
            freshnessMinutes = freshness.minutesAgo,
            freshnessLevel = freshness.level,
        )
    }

    /**
     * Status line for the Vico dashboard graph: BG presence from the overview cache, range from
     * shell UI state, follow/fixed from live scroll heuristics. Freshness still uses the shell
     * pipeline [DashboardEmbeddedComposeState.GraphRenderInput.lastRefreshEpochMs] (updated with
     * each [DashboardShellController.updateGraph]).
     */
    fun presentForVicoDashboard(
        rangeHours: Int,
        hasBgReadings: Boolean,
        viewportFollowingLive: Boolean,
        lastRefreshEpochMs: Long,
        freshnessConfig: DashboardEmbeddedComposeState.GraphFreshnessConfig,
        nowEpochMs: Long,
    ): GraphStatusUi {
        val freshness = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = lastRefreshEpochMs,
            nowEpochMs = nowEpochMs,
            warningThresholdMinutes = freshnessConfig.warningThresholdMinutes,
            staleThresholdMinutes = freshnessConfig.staleThresholdMinutes,
        )
        return GraphStatusUi(
            summaryRangeHours = rangeHours,
            dataStateRes = if (hasBgReadings) R.string.graph_live else R.string.graph_no_data,
            followStateRes = if (viewportFollowingLive) {
                R.string.graph_following
            } else {
                R.string.graph_fixed
            },
            freshnessMessageRes = freshness.messageRes,
            freshnessMinutes = freshness.minutesAgo,
            freshnessLevel = freshness.level,
        )
    }
}
