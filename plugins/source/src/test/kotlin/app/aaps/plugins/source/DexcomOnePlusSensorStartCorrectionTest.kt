package app.aaps.plugins.source

import app.aaps.plugins.source.DexcomOnePlusSensorStartCorrection.Verdict
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The rules of "correct the insertion date": a sensor paired hours after it was inserted used to
 * carry that wrong age for its whole life, with no way to move it.
 */
class DexcomOnePlusSensorStartCorrectionTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    @Test
    fun `an earlier time on the running sensor is accepted`() {
        assertThat(
            DexcomOnePlusSensorStartCorrection.validate(
                newStartMs = now - 8 * hour,
                currentStartMs = now - 2 * hour,
                nowMs = now,
            ),
        ).isEqualTo(Verdict.Accepted)
    }

    @Test
    fun `a later time is accepted too — the app may have been the early one`() {
        assertThat(
            DexcomOnePlusSensorStartCorrection.validate(
                newStartMs = now - 1 * hour,
                currentStartMs = now - 6 * hour,
                nowMs = now,
            ),
        ).isEqualTo(Verdict.Accepted)
    }

    @Test
    fun `the future is refused`() {
        assertThat(
            DexcomOnePlusSensorStartCorrection.validate(
                newStartMs = now + 1,
                currentStartMs = now - 2 * hour,
                nowMs = now,
            ),
        ).isEqualTo(Verdict.InFuture)
    }

    @Test
    fun `older than a whole sensor life is refused`() {
        // 11 days: past the 10 day life plus its 12 h grace, so this is another sensor.
        assertThat(
            DexcomOnePlusSensorStartCorrection.validate(
                newStartMs = now - 11 * day,
                currentStartMs = now - 2 * hour,
                nowMs = now,
            ),
        ).isEqualTo(Verdict.TooOld)
    }

    @Test
    fun `the last hours of the grace window are still allowed`() {
        assertThat(
            DexcomOnePlusSensorStartCorrection.validate(
                newStartMs = now - (10 * day + 11 * hour),
                currentStartMs = now - 2 * hour,
                nowMs = now,
            ),
        ).isEqualTo(Verdict.Accepted)
    }

    @Test
    fun `with no session there is nothing to correct`() {
        assertThat(
            DexcomOnePlusSensorStartCorrection.validate(
                newStartMs = now - 2 * hour,
                currentStartMs = 0L,
                nowMs = now,
            ),
        ).isEqualTo(Verdict.NoSession)
    }

    @Test
    fun `a moment another sensor change already holds is stepped over`() {
        // The database refuses a duplicate timestamp and does not care whether the row it finds is
        // still valid — so the event this correction just invalidated would block the new one, and
        // the display would be left with no valid sensor change at all.
        val taken = setOf(now, now + 1_000L)

        assertThat(DexcomOnePlusSensorStartCorrection.freeTimestamp(now, taken)).isEqualTo(now + 2_000L)
        assertThat(DexcomOnePlusSensorStartCorrection.freeTimestamp(now, emptySet())).isEqualTo(now)
        // One second is invisible in an age shown in hours.
        assertThat(DexcomOnePlusSensorStartCorrection.freeTimestamp(now, setOf(now)) - now).isAtMost(1_000L)
    }

    @Test
    fun `the clean-up reaches back to whichever date is older`() {
        // Moving the date earlier: the plugin's own later event must go, or it stays the "last
        // sensor change" the dashboard reads and the correction is invisible.
        assertThat(DexcomOnePlusSensorStartCorrection.cleanupFrom(newStartMs = now - 8 * hour, currentStartMs = now - 2 * hour))
            .isEqualTo(now - 8 * hour)
        // Moving it later: the old event is the one to remove.
        assertThat(DexcomOnePlusSensorStartCorrection.cleanupFrom(newStartMs = now - 1 * hour, currentStartMs = now - 6 * hour))
            .isEqualTo(now - 6 * hour)
    }
}
