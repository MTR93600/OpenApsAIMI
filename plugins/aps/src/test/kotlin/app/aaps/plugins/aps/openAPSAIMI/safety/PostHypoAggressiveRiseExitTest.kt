package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PostHypoAggressiveRiseExitTest {

    @Test
    fun `exits when bg at or above target plus 20 and delta above 5`() {
        assertThat(PostHypoAggressiveRiseExit.shouldExit(120.0, 100.0, 5.1)).isTrue()
        assertThat(PostHypoAggressiveRiseExit.shouldExit(160.0, 100.0, 20.0)).isTrue()
    }

    @Test
    fun `stays guarded when delta is not a real rise`() {
        assertThat(PostHypoAggressiveRiseExit.shouldExit(160.0, 100.0, 5.0)).isFalse()
        assertThat(PostHypoAggressiveRiseExit.shouldExit(160.0, 100.0, 0.0)).isFalse()
        assertThat(PostHypoAggressiveRiseExit.shouldExit(160.0, 100.0, -3.0)).isFalse()
    }

    @Test
    fun `stays guarded when bg still inside target plus 20`() {
        assertThat(PostHypoAggressiveRiseExit.shouldExit(119.0, 100.0, 20.0)).isFalse()
    }
}
