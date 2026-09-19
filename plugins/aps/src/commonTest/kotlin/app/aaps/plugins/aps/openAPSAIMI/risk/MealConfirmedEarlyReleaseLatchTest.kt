package app.aaps.plugins.aps.openAPSAIMI.risk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Episode-bound latch for meal-confirmed early release.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `11f064dd48` (file SHA `415796e4e9`, unchanged on tip
 * `a546722609`). Study source set: [MealConfirmedEarlyReleaseLatch] is commonMain and has no mocks,
 * so the tests live in `commonTest` (`kotlin.test`) — same layout as
 * [app.aaps.plugins.aps.openAPSAIMI.basal.AnticipationBasalFloorTest]. Assertions are the same locks
 * as the Truth/JUnit reference corpus.
 */
class MealConfirmedEarlyReleaseLatchTest {

    private val target = 105.0

    /**
     * Nothing can latch before MCER has armed, so every test of a trip has to arm first. That is the
     * contract change of 2026-09-16: the earlier version latched on any falling tick and held the
     * release off through the next meal.
     */
    private fun armed(iobU: Double = 9.0, bgMgdl: Double = 220.0) =
        MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            armedThisTick = true,
            tailTripped = false,
            bgMgdl = bgMgdl,
            targetBgMgdl = target,
            iobU = iobU,
        )

    @Test
    fun withoutATripNothingIsHeld() {
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = 9.0,
        )
        assertFalse(state.latched)
    }

    @Test
    fun aTripLatchesAndRemembersTheInsulinOnBoard() {
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = armed(),
            tailTripped = true,
            bgMgdl = 265.0,
            targetBgMgdl = target,
            iobU = 11.63,
        )
        assertTrue(state.latched)
        assertEquals(11.63, state.iobAtTripU, absoluteTolerance = 1e-9)
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
            previous = armed(),
            tailTripped = true,
            bgMgdl = 265.0,
            targetBgMgdl = target,
            iobU = 11.63,
        )
        assertTrue(state.latched)
        // 14:38 — the phase flips away and glucose steps up, so nothing trips on this tick.
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 240.2,
            targetBgMgdl = target,
            iobU = 8.87,
        )
        assertTrue(state.latched)
        assertEquals(11.63, state.iobAtTripU, absoluteTolerance = 1e-9)
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
        assertFalse(state.latched)
        assertEquals(0.0, state.iobAtTripU)
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
        assertFalse(state.latched)
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
        assertTrue(state.latched)
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
        assertTrue(state.latched)
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
        assertFalse(state.latched)
    }

    /**
     * An unreadable stack used to be remembered as 0, which closed the insulin way out and left
     * glucose as the only release. Since 2026-09-16 the remembered stack is floored at
     * [MealConfirmedEarlyReleaseLatch.MIN_TRIP_IOB_U], so a latch can never be held by a number
     * nobody could read.
     */
    @Test
    fun aTripWithoutAUsableInsulinReadingRemembersTheFlooredStack() {
        var state = MealConfirmedEarlyReleaseLatch.next(
            previous = armed(),
            tailTripped = true,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = Double.NaN,
        )
        assertTrue(state.latched)
        assertEquals(MealConfirmedEarlyReleaseLatch.MIN_TRIP_IOB_U, state.iobAtTripU)
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = MealConfirmedEarlyReleaseLatch.MIN_TRIP_IOB_U,
        )
        assertTrue(state.latched)
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = 0.0,
        )
        assertFalse(state.latched)
    }

    @Test
    fun aNegativeInsulinOnBoardAtTheTripFallsBackToTheFlooredStack() {
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = armed(),
            tailTripped = true,
            bgMgdl = 240.0,
            targetBgMgdl = target,
            iobU = -1.5,
        )
        assertTrue(state.latched)
        assertEquals(MealConfirmedEarlyReleaseLatch.MIN_TRIP_IOB_U, state.iobAtTripU)
    }

    /**
     * The regression this test exists for. Reported from the field on 2026-09-16: the latch tripped
     * on an ordinary pre-meal descent — glucose drifting down from a morning high at low insulin on
     * board — and then held the early release off for the whole of the next meal, because neither
     * way out is reachable while a meal rises: glucose never goes back under `target + 20`, and
     * insulin on board only grows.
     *
     * A falling tick while the early release has never armed is **not** a post-peak tail. There was
     * no peak. Nothing to guard against, so nothing may latch.
     */
    @Test
    fun aFallWithNoEarlierArmDoesNotLatch() {
        val state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            tailTripped = true,
            bgMgdl = 126.0,
            targetBgMgdl = 100.0,
            iobU = 1.71,
        )
        assertFalse(state.latched)
    }

    /**
     * The field case, end to end. Glucose drifts down from 159 to 126 with insulin on board falling
     * 6.05 → 1.71 and the early release never armed; then lunch starts from 126. The release must be
     * free to arm on the rise.
     */
    @Test
    fun thePreMealDescentOf0916LeavesTheReleaseFreeToArm() {
        var state = MealConfirmedEarlyReleaseLatch.State()
        val descent = listOf(159.0 to 6.05, 150.0 to 4.80, 140.0 to 3.40, 132.0 to 2.30, 126.0 to 1.71)
        for ((bg, iob) in descent) {
            state = MealConfirmedEarlyReleaseLatch.next(
                previous = state,
                tailTripped = true,
                bgMgdl = bg,
                targetBgMgdl = 100.0,
                iobU = iob,
            )
        }
        assertFalse(state.latched)
    }

    /**
     * And the guard must still do its job once there really has been an early release. Same shape as
     * the 2026-09-15 case: the release arms on the rise, the tail shows itself, and one tick of a
     * phase flip with a sensor step up must not undo it.
     */
    @Test
    fun aTailAfterARealArmStillLatchesAndHolds() {
        var state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            armedThisTick = true,
            tailTripped = false,
            bgMgdl = 220.0,
            targetBgMgdl = 105.0,
            iobU = 9.0,
        )
        assertFalse(state.latched)

        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = true,
            bgMgdl = 265.0,
            targetBgMgdl = 105.0,
            iobU = 11.63,
        )
        assertTrue(state.latched)

        // 14:38 — the phase leaves PEAK_CORRECTION for one tick and the sensor steps up.
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 240.2,
            targetBgMgdl = 105.0,
            iobU = 8.87,
        )
        assertTrue(state.latched)
    }

    /**
     * Belt for the same failure: even after a real arm, a trip at a small stack must not set a
     * release threshold below the insulin any meal correction immediately creates.
     */
    @Test
    fun theRememberedStackIsFlooredSoTheWayOutStaysReachable() {
        var state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            armedThisTick = true,
            tailTripped = false,
            bgMgdl = 200.0,
            targetBgMgdl = 100.0,
            iobU = 1.71,
        )
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = true,
            bgMgdl = 200.0,
            targetBgMgdl = 100.0,
            iobU = 1.71,
        )
        assertTrue(state.latched)
        assertTrue(state.iobAtTripU >= MealConfirmedEarlyReleaseLatch.MIN_TRIP_IOB_U)
    }

    @Test
    fun aReleaseAlsoForgetsThatTheReleaseHadArmed() {
        var state = MealConfirmedEarlyReleaseLatch.next(
            previous = MealConfirmedEarlyReleaseLatch.State(),
            armedThisTick = true,
            tailTripped = false,
            bgMgdl = 200.0,
            targetBgMgdl = 100.0,
            iobU = 9.0,
        )
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = true,
            bgMgdl = 200.0,
            targetBgMgdl = 100.0,
            iobU = 9.0,
        )
        assertTrue(state.latched)
        // Glucose back near target ends the episode.
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = false,
            bgMgdl = 110.0,
            targetBgMgdl = 100.0,
            iobU = 4.0,
        )
        assertFalse(state.latched)
        assertFalse(state.armedSeen)
        // And a bare fall after that episode must not latch again.
        state = MealConfirmedEarlyReleaseLatch.next(
            previous = state,
            tailTripped = true,
            bgMgdl = 150.0,
            targetBgMgdl = 100.0,
            iobU = 2.0,
        )
        assertFalse(state.latched)
    }

    @Test
    fun aStateThatIsNotLatchedAlwaysCountsAsReleased() {
        assertTrue(
            MealConfirmedEarlyReleaseLatch.releases(
                MealConfirmedEarlyReleaseLatch.State(),
                bgMgdl = 240.0,
                targetBgMgdl = target,
                iobU = 20.0,
            )
        )
    }

    @Test
    fun aGlucoseThatIsNotAUsableNumberDoesNotRelease() {
        val latched = MealConfirmedEarlyReleaseLatch.State(latched = true, iobAtTripU = 12.0)
        assertFalse(
            MealConfirmedEarlyReleaseLatch.releases(latched, Double.NaN, target, iobU = 12.0)
        )
    }
}
