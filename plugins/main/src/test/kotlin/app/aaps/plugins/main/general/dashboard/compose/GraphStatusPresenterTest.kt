package app.aaps.plugins.main.general.dashboard.compose

import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GraphStatusPresenterTest {

    @Test
    fun `maps render input into summary labels`() {
        val renderInput = DashboardEmbeddedComposeState.GraphRenderInput(
            rangeHours = 12,
            hasBgData = true,
            followLive = false,
            lastRefreshEpochMs = 1_000L,
        )
        val cfg = DashboardEmbeddedComposeState.GraphFreshnessConfig(
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        val result = GraphStatusPresenter.present(
            renderInput = renderInput,
            freshnessConfig = cfg,
            nowEpochMs = 4 * 60_000L,
        )

        assertThat(result.summaryRangeHours).isEqualTo(12)
        assertThat(result.dataStateRes).isEqualTo(R.string.graph_live)
        assertThat(result.followStateRes).isEqualTo(R.string.graph_fixed)
    }

    @Test
    fun `graph pan forces fixed follow label even when followLive true`() {
        val renderInput = DashboardEmbeddedComposeState.GraphRenderInput(
            rangeHours = 6,
            hasBgData = true,
            followLive = true,
            graphPanActive = true,
            lastRefreshEpochMs = 1_000L,
        )
        val cfg = DashboardEmbeddedComposeState.GraphFreshnessConfig(
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        val result = GraphStatusPresenter.present(
            renderInput = renderInput,
            freshnessConfig = cfg,
            nowEpochMs = 4 * 60_000L,
        )

        assertThat(result.followStateRes).isEqualTo(R.string.graph_fixed)
    }

    @Test
    fun `maps stale freshness state from policy`() {
        val renderInput = DashboardEmbeddedComposeState.GraphRenderInput(
            rangeHours = 6,
            hasBgData = false,
            followLive = true,
            lastRefreshEpochMs = 0L,
        )
        val cfg = DashboardEmbeddedComposeState.GraphFreshnessConfig(
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        val unknown = GraphStatusPresenter.present(
            renderInput = renderInput,
            freshnessConfig = cfg,
            nowEpochMs = 1_000L,
        )
        assertThat(unknown.freshnessLevel).isEqualTo(GraphFreshnessLevel.UNKNOWN)
        assertThat(unknown.freshnessMessageRes).isEqualTo(R.string.graph_refresh_unknown)

        val stale = GraphStatusPresenter.present(
            renderInput = renderInput.copy(lastRefreshEpochMs = 1_000L),
            freshnessConfig = cfg,
            nowEpochMs = 20 * 60_000L,
        )
        assertThat(stale.freshnessLevel).isEqualTo(GraphFreshnessLevel.STALE)
        assertThat(stale.freshnessMessageRes).isEqualTo(R.string.graph_refresh_minutes_ago)
    }
}
