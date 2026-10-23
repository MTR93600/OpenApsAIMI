package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The aggressive-rise SMB floor must stop at the ceiling the maxSMB ladder chose for the tick.
 *
 * The floor used to be capped by `max(maxSMB, maxSMBHB)`, so it took the high-BG ceiling even on
 * ticks where the ladder had refused to promote. Over three support packages: on 36 of 50
 * floor-active ticks the floor landed exactly on `maxSMBHB`, and on 31 of those 50 the ladder had
 * chosen a lower value.
 *
 * The fixtures are real ticks:
 *  - dinner 2026-08-14 20:41 - ladder on standard 1.60 U, floor took 2.70 U; five such ticks drove
 *    IOB from 5.73 to 19.32 U and BG to 65.7 mg/dL. This is the case the change fixes.
 *  - lunch 2026-08-15 13:57 - ladder had already promoted, so `maxSMB` equals `maxSMBHB` at 1.80 U
 *    and the floor is unchanged. This is the rescue the change must not break.
 *  - the 2026-08-12 crash ticks at BG 287-297 - the BG >= 250 branch promotes, so again unchanged.
 *
 * These tests mirror the clamp around `smbCeilingForFloor`.
 *
 * Port of `RiseFloorLadderCeilingTest` from `dev_OAPSAIMI`. Truth/JUnit replaced by `kotlin.test`,
 * names in camelCase. On this branch the clamp lives in `effects/RunAutodriveV3.kt`
 * (`state.maxSmb`, `state.maxIob - state.iob`); it was compared with dev
 * `DetermineBasalAIMI2.determine_basal` and is the same.
 */
class RiseFloorLadderCeilingTest {

    /**
     * The clamp under test: the floor is bounded by the ladder ceiling and by the IOB headroom.
     * `maxSmbHighBg` is not used on purpose - that is the whole point of the change.
     */
    private fun boundedFloorU(
        tierFloorU: Double,
        maxSmb: Double,
        maxIob: Double,
        iob: Double,
    ): Double {
        val iobHeadroom = (maxIob - iob).coerceAtLeast(0.0)
        val ceiling = maxSmb.coerceAtLeast(0.0)
        return tierFloorU.coerceAtMost(min(ceiling, iobHeadroom))
    }

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected but was $actual")
    }

    @Test
    fun theFloorNeverExceedsTheLadderCeiling() {
        // Dinner 2026-08-14 20:41: tier floor 3.0 U pref, ladder on standard 1.60 U.
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 1.60, maxIob = 20.0, iob = 8.33)

        assertNear(1.60, floor)
    }

    @Test
    fun aHighBgCeilingAboveTheLadderCeilingNoLongerLiftsTheFloor() {
        // The old clamp used max(maxSMB, maxSMBHB) = 2.70 U here and delivered exactly that.
        val maxSmb = 1.60
        val maxSmbHighBg = 2.70
        val old = 3.0.coerceAtMost(min(maxOf(maxSmb, maxSmbHighBg), 20.0 - 8.33))
        val new = boundedFloorU(tierFloorU = 3.0, maxSmb = maxSmb, maxIob = 20.0, iob = 8.33)

        assertNear(2.70, old)
        assertNear(1.60, new)
        assertTrue(new < old)
    }

    @Test
    fun aPromotedLadderLeavesTheFloorUntouched() {
        // Lunch 2026-08-15 13:57: the ladder had promoted, so maxSMB equals maxSMBHB.
        val maxSmb = 1.80
        val maxSmbHighBg = 1.80
        val old = 3.0.coerceAtMost(min(maxOf(maxSmb, maxSmbHighBg), 20.0 - 10.72))
        val new = boundedFloorU(tierFloorU = 3.0, maxSmb = maxSmb, maxIob = 20.0, iob = 10.72)

        assertNear(old, new)
        assertNear(1.80, new)
    }

    @Test
    fun theBgOver250BranchStillPromotesSoTheCrashTicksAreUnchanged() {
        // 2026-08-12 10:37, BG 287.4: the plateau branch had already set maxSMB to maxSMBHB.
        val new = boundedFloorU(tierFloorU = 2.20, maxSmb = 2.20, maxIob = 20.0, iob = 8.59)

        assertNear(2.20, new)
    }

    @Test
    fun theFloorStaysFiniteAndNonNegativeWhenTheLadderCeilingIsZero() {
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 0.0, maxIob = 20.0, iob = 5.0)

        assertTrue(floor.isFinite())
        assertNear(0.0, floor)
        assertTrue(floor >= 0.0)
    }

    @Test
    fun aNegativeLadderCeilingCannotInventANegativeDose() {
        // Defensive: the ladder should never hand down a negative value, but the clamp must not
        // turn one into a negative bolus if it ever does.
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = -1.0, maxIob = 20.0, iob = 5.0)

        assertTrue(floor.isFinite())
        assertTrue(floor >= 0.0)
    }

    @Test
    fun theIobHeadroomStillBindsWhenItIsTighterThanTheLadderCeiling() {
        // Push IOB high enough that the headroom is tighter than the 1.60 U ladder ceiling.
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 1.60, maxIob = 20.0, iob = 19.2)

        assertNear(0.8, floor)
        assertTrue(floor <= 1.60)
    }

    @Test
    fun noHeadroomMeansNoFloor() {
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 1.60, maxIob = 20.0, iob = 25.0)

        assertNear(0.0, floor)
        assertTrue(floor >= 0.0)
    }
}
