package app.aaps.database

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class TemporaryBasalQueryBoundsTest {

    // Medtrum stores a pump suspend as a 0 % temp basal of this length (MedtrumPump.FAKE_TBR_LENGTH, in minutes)
    // and cuts it at the resume. :database:impl cannot see :pump:medtrum, so the number is repeated here.
    private val medtrumSuspendMs = TimeUnit.MINUTES.toMillis(4800)

    @Test
    fun `a Medtrum suspend temp basal is not dropped as overlong`() {
        // Below this, the suspend is not found while it runs, the resume cannot cut it, and the IOB code
        // (which reads temp basals without this bound) sees zero basal for up to 80 hours.
        assertThat(TemporaryBasalQueryBounds.MAX_REASONABLE_DURATION_MS).isAtLeast(medtrumSuspendMs)
    }

    @Test
    fun `the lookback reaches the start of the longest accepted temp basal`() {
        assertThat(TemporaryBasalQueryBounds.MAX_ACTIVE_LOOKBACK_MS).isAtLeast(TemporaryBasalQueryBounds.MAX_REASONABLE_DURATION_MS)
    }

    @Test
    fun `the earliest timestamp is never negative`() {
        assertThat(TemporaryBasalQueryBounds.earliestTimestampForActiveAt(1000L)).isEqualTo(0L)
    }
}
