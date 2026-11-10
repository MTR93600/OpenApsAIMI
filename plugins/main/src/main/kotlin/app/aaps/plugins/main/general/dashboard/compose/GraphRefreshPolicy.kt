package app.aaps.plugins.main.general.dashboard.compose

import androidx.annotation.StringRes
import app.aaps.plugins.main.R

internal enum class GraphFreshnessLevel {
    FRESH,
    WARNING,
    STALE,
    UNKNOWN,
}

internal data class GraphFreshnessUi(
    val level: GraphFreshnessLevel,
    @StringRes val messageRes: Int,
    val minutesAgo: Int? = null,
)

/**
 * Product-level freshness policy for dashboard graph status.
 */
internal object GraphRefreshPolicy {
    fun evaluate(
        lastRefreshEpochMs: Long,
        nowEpochMs: Long,
        warningThresholdMinutes: Int,
        staleThresholdMinutes: Int,
    ): GraphFreshnessUi {
        if (lastRefreshEpochMs <= 0L) {
            return GraphFreshnessUi(
                level = GraphFreshnessLevel.UNKNOWN,
                messageRes = R.string.graph_refresh_unknown,
            )
        }
        val ageMinutes = ((nowEpochMs - lastRefreshEpochMs).coerceAtLeast(0L) / 60_000L).toInt()
        return when {
            ageMinutes <= 0 -> GraphFreshnessUi(
                level = GraphFreshnessLevel.FRESH,
                messageRes = R.string.graph_refresh_now,
            )
            ageMinutes < warningThresholdMinutes -> GraphFreshnessUi(
                level = GraphFreshnessLevel.FRESH,
                messageRes = R.string.graph_refresh_minutes_ago,
                minutesAgo = ageMinutes,
            )
            ageMinutes < staleThresholdMinutes -> GraphFreshnessUi(
                level = GraphFreshnessLevel.WARNING,
                messageRes = R.string.graph_refresh_minutes_ago,
                minutesAgo = ageMinutes,
            )
            else -> GraphFreshnessUi(
                level = GraphFreshnessLevel.STALE,
                messageRes = R.string.graph_refresh_minutes_ago,
                minutesAgo = ageMinutes,
            )
        }
    }
}
