package app.aaps.core.ui.compose.dashboard

import app.aaps.core.ui.compose.formatKmp

/**
 * Product-level freshness policy for dashboard graph status.
 *
 * Ported from `plugins/main` — Android string resources are replaced by
 * caller-provided [GraphFreshnessStrings] so this stays in commonMain.
 */
enum class GraphFreshnessLevel {
    FRESH,
    WARNING,
    STALE,
    UNKNOWN,
}

/** Resolved strings for [GraphRefreshPolicy]; the platform supplies them. */
data class GraphFreshnessStrings(
    val unknown: String,
    val now: String,
    /** Supports a "%d" (or "{}") placeholder for the minute count. */
    val minutesAgo: String,
)

internal data class GraphFreshnessUi(
    val level: GraphFreshnessLevel,
    val message: String,
    val minutesAgo: Int? = null,
)

internal object GraphRefreshPolicy {
    fun evaluate(
        lastRefreshEpochMs: Long,
        nowEpochMs: Long,
        warningThresholdMinutes: Int,
        staleThresholdMinutes: Int,
        strings: GraphFreshnessStrings,
    ): GraphFreshnessUi {
        if (lastRefreshEpochMs <= 0L) {
            return GraphFreshnessUi(
                level = GraphFreshnessLevel.UNKNOWN,
                message = strings.unknown,
            )
        }
        val ageMinutes = ((nowEpochMs - lastRefreshEpochMs).coerceAtLeast(0L) / 60_000L).toInt()
        return when {
            ageMinutes <= 0 -> GraphFreshnessUi(
                level = GraphFreshnessLevel.FRESH,
                message = strings.now,
            )
            ageMinutes < warningThresholdMinutes -> GraphFreshnessUi(
                level = GraphFreshnessLevel.FRESH,
                message = strings.minutesAgo.formatKmp(ageMinutes),
                minutesAgo = ageMinutes,
            )
            ageMinutes < staleThresholdMinutes -> GraphFreshnessUi(
                level = GraphFreshnessLevel.WARNING,
                message = strings.minutesAgo.formatKmp(ageMinutes),
                minutesAgo = ageMinutes,
            )
            else -> GraphFreshnessUi(
                level = GraphFreshnessLevel.STALE,
                message = strings.minutesAgo.formatKmp(ageMinutes),
                minutesAgo = ageMinutes,
            )
        }
    }
}
