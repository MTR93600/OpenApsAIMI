package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CompressionReboundGuardTest {

    @Test
    fun `delta at threshold is not blocked`() {
        assertThat(CompressionReboundGuard.isImpossibleRise(CompressionReboundGuard.DELTA_THRESHOLD_MGDL_PER_5MIN))
            .isFalse()
    }

    @Test
    fun `delta above threshold triggers guard`() {
        assertThat(CompressionReboundGuard.isImpossibleRise(36f)).isTrue()
    }
}
