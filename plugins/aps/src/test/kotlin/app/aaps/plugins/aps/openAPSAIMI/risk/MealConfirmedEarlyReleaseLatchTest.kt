package app.aaps.plugins.aps.openAPSAIMI.risk

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealConfirmedEarlyReleaseLatchTest {

    private val target = 105.0

    @Test
    fun withoutATripNothingIsHeld() {
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = 9.0,
        )
        assertThat(state.latched).isFalse()
    }

    @Test
    fun aTripLatchesAndRemembersTheInsulinOnBoard() {
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            tailTripped = true,
            bgMgdl = 265.0,
            targetBgMgdl = target,
            iobU = 11.63,
        )
        assertThat(state.latched).isTrue()
        assertThat(state.iobAtTripU).isWithin(1e-9).of(11.63)
    }

    /**
     * The 2026-09-15 lunch, read off the export. Peak 13:52 with 11.63 U on board, phase breaker
     * tripped through 14:32, then at 14:38 the phase left `PEAK_CORRECTION` for one tick while the
     * sensor stepped 215.8 to 240.2. Before the latch all three breakers released together and the
     * loop went back to the ceiling basal with 8.87 U still working.
     */
    @Test
    fun theOneNoisyTickOf0915CannotUndoTheBreaker() {
        var state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            tailTripped = true,
            bgMgdl = 265.0,
            targetBgMgdl = target,
            iobU = 11.63,
        )
        assertThat(state.latched).isTrue()
        // 14:38 — the phase flips away and glucose steps up, so nothing trips on this tick.
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 240.2,
            targetBgMgdl = target,
            iobU = 8.87,
        )
        assertThat(state.latched).isTrue()
        assertThat(state.iobAtTripU).isWithin(1e-9).of(11.63)
    }

    @Test
    fun theLatchReleasesWhenGlucoseComesBackNearTarget() {
        val latched = MealConfirmedEarlyReleaseLatch.State(latched = true, iobAtTripU = 11.63)
        // 124 is under target + 20, so the early release could not arm anyway.
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = latched,
            tailTripped = false,
            bgMgdl = 124.0,
            targetBgMgdl = target,
            iobU = 6.0,
        )
        assertThat(state.latched).isFalse()
        assertThat(state.iobAtTripU).isEqualTo(0.0)
    }

    @Test
    fun theLatchReleasesWhenTheStackHasLargelyGone() {
        val latched = MealConfirmedEarlyReleaseLatch.State(latched = true, iobAtTripU = 12.0)
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = latched,
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = 6.0,
        )
        assertThat(state.latched).isFalse()
    }

    @Test
    fun theLatchHoldsWhileTheStackIsStillWorkingAndGlucoseIsHigh() {
        val latched = MealConfirmedEarlyReleaseLatch.State(latched = true, iobAtTripU = 12.0)
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = latched,
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = 8.87,
        )
        assertThat(state.latched).isTrue()
    }

    @Test
    fun exactlyOnTheGlucoseMarginTheLatchStillHolds() {
        val latched = MealConfirmedEarlyReleaseLatch.State(latched = true, iobAtTripU = 12.0)
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = latched,
            tailTripped = false,
            bgMgdl = target + MealConfirmedEarlyReleaseLatch.BG_MARGIN_MGDL,
            targetBgMgdl = target,
            iobU = 12.0,
        )
        assertThat(state.latched).isTrue()
    }

    @Test
    fun exactlyOnTheInsulinFractionTheLatchReleases() {
        val latched = MealConfirmedEarlyReleaseLatch.State(latched = true, iobAtTripU = 12.0)
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = latched,
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = 12.0 * MealConfirmedEarlyReleaseLatch.IOB_RELEASE_FRACTION,
        )
        assertThat(state.latched).isFalse()
    }

    @Test
    fun aTripWithoutAUsableInsulinReadingStillLatchesAndReleasesOnGlucose() {
        var state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            tailTripped = true,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = Double.NaN,
        )
        assertThat(state.latched).isTrue()
        assertThat(state.iobAtTripU).isEqualTo(0.0)
        // With no remembered stack the insulin way out is closed, so glucose must carry the release.
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = 0.0,
        )
        assertThat(state.latched).isTrue()
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 110.0,
            targetBgMgdl = target,
            iobU = 0.0,
        )
        assertThat(state.latched).isFalse()
    }

    @Test
    fun aNegativeInsulinOnBoardAtTheTripIsNotRemembered() {
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            tailTripped = true,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = -1.5,
        )
        assertThat(state.latched).isTrue()
        assertThat(state.iobAtTripU).isEqualTo(0.0)
    }

    @Test
    fun aStateThatIsNotLatchedAlwaysCountsAsReleased() {
        assertThat(
            MealConfirmedEarlyReleaseLatch.releases(
                MealConfirmedEarlyReleaseLatch.State(),
                bgMgdl = 240.0,
                targetBgMgdl = target,
                iobU = 20.0,
            )
        ).isTrue()
    }

    @Test
    fun aGlucoseThatIsNotAUsableNumberDoesNotRelease() {
        val latched = MealConfirmedEarlyReleaseLatch.State(latched = true, iobAtTripU = 12.0)
        assertThat(
            MealConfirmedEarlyReleaseLatch.releases(latched, Double.NaN, target, iobU = 12.0)
        ).isFalse()
    }
}
