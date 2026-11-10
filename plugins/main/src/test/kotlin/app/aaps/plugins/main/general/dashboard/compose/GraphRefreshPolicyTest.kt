package app.aaps.plugins.main.general.dashboard.compose

import app.aaps.plugins.main.R
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GraphRefreshPolicyTest {

    @Test
    fun `returns UNKNOWN when last refresh is not set`() {
        val result = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = 0L,
            nowEpochMs = 1_000L,
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        assertThat(result.level).isEqualTo(GraphFreshnessLevel.UNKNOWN)
        assertThat(result.messageRes).isEqualTo(R.string.graph_refresh_unknown)
        assertThat(result.minutesAgo).isNull()
    }

    @Test
    fun `returns FRESH now when age is zero`() {
        val now = 1_000_000L
        val result = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = now,
            nowEpochMs = now,
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        assertThat(result.level).isEqualTo(GraphFreshnessLevel.FRESH)
        assertThat(result.messageRes).isEqualTo(R.string.graph_refresh_now)
        assertThat(result.minutesAgo).isNull()
    }

    @Test
    fun `returns FRESH minutes ago below warning threshold`() {
        val now = 10 * 60_000L
        val result = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = now - 3 * 60_000L,
            nowEpochMs = now,
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        assertThat(result.level).isEqualTo(GraphFreshnessLevel.FRESH)
        assertThat(result.messageRes).isEqualTo(R.string.graph_refresh_minutes_ago)
        assertThat(result.minutesAgo).isEqualTo(3)
    }

    @Test
    fun `returns WARNING between warning and stale thresholds`() {
        val now = 20 * 60_000L
        val result = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = now - 7 * 60_000L,
            nowEpochMs = now,
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        assertThat(result.level).isEqualTo(GraphFreshnessLevel.WARNING)
        assertThat(result.messageRes).isEqualTo(R.string.graph_refresh_minutes_ago)
        assertThat(result.minutesAgo).isEqualTo(7)
    }

    @Test
    fun `returns STALE at or above stale threshold`() {
        val now = 40 * 60_000L
        val result = GraphRefreshPolicy.evaluate(
            lastRefreshEpochMs = now - 15 * 60_000L,
            nowEpochMs = now,
            warningThresholdMinutes = 5,
            staleThresholdMinutes = 15,
        )

        assertThat(result.level).isEqualTo(GraphFreshnessLevel.STALE)
        assertThat(result.messageRes).isEqualTo(R.string.graph_refresh_minutes_ago)
        assertThat(result.minutesAgo).isEqualTo(15)
    }
}
