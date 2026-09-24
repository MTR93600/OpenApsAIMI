package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PostHypoAggressiveRiseExitTest {

    @Test
    fun `exits when bg at or above target plus 30 and delta above 15`() {
        assertThat(PostHypoAggressiveRiseExit.shouldExit(130.0, 100.0, 15.1)).isTrue()
        assertThat(PostHypoAggressiveRiseExit.shouldExit(160.0, 100.0, 20.0)).isTrue()
    }

    @Test
    fun `stays guarded when delta is not aggressive`() {
        assertThat(PostHypoAggressiveRiseExit.shouldExit(160.0, 100.0, 15.0)).isFalse()
        assertThat(PostHypoAggressiveRiseExit.shouldExit(160.0, 100.0, 8.0)).isFalse()
    }

    @Test
    fun `stays guarded when bg still inside target plus 30`() {
        assertThat(PostHypoAggressiveRiseExit.shouldExit(129.0, 100.0, 20.0)).isFalse()
    }
}
