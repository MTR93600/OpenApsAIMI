package app.aaps.plugins.main.general.dashboard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GraphFreshnessConfigResolverTest {

    @Test
    fun `clamps stale threshold to minimum`() {
        val config = GraphFreshnessConfigResolver.fromStaleThresholdMinutes(1)

        assertThat(config.staleThresholdMinutes).isEqualTo(5)
        assertThat(config.warningThresholdMinutes).isEqualTo(2)
    }

    @Test
    fun `clamps stale threshold to maximum`() {
        val config = GraphFreshnessConfigResolver.fromStaleThresholdMinutes(999)

        assertThat(config.staleThresholdMinutes).isEqualTo(240)
        assertThat(config.warningThresholdMinutes).isEqualTo(120)
    }

    @Test
    fun `warning threshold stays below stale threshold`() {
        val config = GraphFreshnessConfigResolver.fromStaleThresholdMinutes(15)

        assertThat(config.warningThresholdMinutes).isLessThan(config.staleThresholdMinutes)
        assertThat(config.warningThresholdMinutes).isEqualTo(7)
        assertThat(config.staleThresholdMinutes).isEqualTo(15)
    }
}
