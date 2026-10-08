package app.aaps.core.ui.compose.dashboard

/**
 * Presents the dashboard graph status line.
 *
 * Ported from `plugins/main` — Android string resources are replaced by
 * caller-provided strings so this stays in commonMain. The Android
 * `DashboardEmbeddedComposeState.GraphRenderInput` / `GraphFreshnessConfig`
 * are replaced by the plain [GraphStatusInput] below.
 */
internal data class GraphStatusInput(
    val rangeHours: Int,
    val hasBgData: Boolean,
    val followLive: Boolean,
    val graphPanActive: Boolean,
    val lastRefreshEpochMs: Long,
    val warningThresholdMinutes: Int = 5,
    val staleThresholdMinutes: Int = 15,
)

/** Resolved strings for [GraphStatusPresenter]; the platform supplies them. */
data class GraphStatusStrings(
    val live: String,
    val noData: String,
    val following: String,
    val fixed: String,
    val freshness: GraphFreshnessStrings,
)

internal data class GraphStatusUi(
    val summaryRangeHours: Int,
    val dataState: String,
    val followState: String,
    val freshnessMessage: String,
    val freshnessMinutes: Int?,
    val freshnessLevel: GraphFreshnessLevel,
)

internal object GraphStatusPresenter {
    fun present(
        input: GraphStatusInput,
        strings: GraphStatusStrings,
        nowEpochMs: Long,
    ): GraphStatusUi {
        val freshness = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = input.lastRefreshEpochMs,
            nowEpochMs = nowEpochMs,
            warningThresholdMinutes = input.warningThresholdMinutes,
            staleThresholdMinutes = input.staleThresholdMinutes,
            strings = strings.freshness,
        )
        return GraphStatusUi(
            summaryRangeHours = input.rangeHours,
            dataState = if (input.hasBgData) strings.live else strings.noData,
            followState = if (input.followLive && !input.graphPanActive) {
                strings.following
            } else {
                strings.fixed
            },
            freshnessMessage = freshness.message,
            freshnessMinutes = freshness.minutesAgo,
            freshnessLevel = freshness.level,
        )
    }

    /**
     * Status line for the Vico dashboard graph: BG presence from the overview cache, range from
     * shell UI state, follow/fixed from live scroll heuristics.
     */
    fun presentForVicoDashboard(
        rangeHours: Int,
        hasBgReadings: Boolean,
        viewportFollowingLive: Boolean,
        lastRefreshEpochMs: Long,
        warningThresholdMinutes: Int = 5,
        staleThresholdMinutes: Int = 15,
        strings: GraphStatusStrings,
        nowEpochMs: Long,
    ): GraphStatusUi {
        val freshness = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = lastRefreshEpochMs,
            nowEpochMs = nowEpochMs,
            warningThresholdMinutes = warningThresholdMinutes,
            staleThresholdMinutes = staleThresholdMinutes,
            strings = strings.freshness,
        )
        return GraphStatusUi(
            summaryRangeHours = rangeHours,
            dataState = if (hasBgReadings) strings.live else strings.noData,
            followState = if (viewportFollowingLive) {
                strings.following
            } else {
                strings.fixed
            },
            freshnessMessage = freshness.message,
            freshnessMinutes = freshness.minutesAgo,
            freshnessLevel = freshness.level,
        )
    }
}
