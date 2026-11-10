package app.aaps.plugins.main.general.dashboard

internal object GraphFreshnessConfigResolver {
    fun fromStaleThresholdMinutes(staleThresholdMinutes: Int): DashboardEmbeddedComposeState.GraphFreshnessConfig {
        val staleMinutes = staleThresholdMinutes.coerceIn(5, 240)
        // Keep warning strictly below stale with a practical early-warning window.
        val warningMinutes = (staleMinutes / 2).coerceIn(2, staleMinutes - 1)
        return DashboardEmbeddedComposeState.GraphFreshnessConfig(
            warningThresholdMinutes = warningMinutes,
            staleThresholdMinutes = staleMinutes,
        )
    }
}
