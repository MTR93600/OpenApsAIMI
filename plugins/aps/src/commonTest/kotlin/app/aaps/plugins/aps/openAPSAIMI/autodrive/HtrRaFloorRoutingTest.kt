package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hyper-trajectory Ra floor lifts what the **controller** expects, never what the estimator
 * believes and never what the safety barrier judges against.
 *
 * The floor used to be passed as `AutoDriveState.estimatedRa` and dropped: `AutodriveEngine.tick`
 * overwrote the field with `stateEstimator.getLastRa()` before the estimator ran. `ControlBarrierShield`
 * reads `estimatedRa` to size its own margins; giving it an optimistic value would stop it being a
 * barrier. These assertions pin the routing rule rather than the arithmetic.
 *
 * Port of `HtrRaFloorRoutingTest` from `dev_OAPSAIMI` (plugins/aps/src/test). The selection in
 * `AutodriveEngine.tick` (`mpcState`) is the same on this branch. Adapted: Truth replaced by
 * `kotlin.test`, backtick names by camelCase, moved to `commonTest`.
 */
class HtrRaFloorRoutingTest {

    private fun state(ra: Double) = AutoDriveState.createSafe(
        bg = 180.0,
        bgVelocity = 2.0,
        iob = 2.0,
        estimatedRa = ra,
        physiologicalStressMask = DoubleArray(4),
    )

    /** Mirrors the selection in `AutodriveEngine.tick`. */
    private fun mpcView(estimated: AutoDriveState, floor: Double): AutoDriveState =
        if (floor.isFinite() && floor > estimated.estimatedRa) estimated.copy(estimatedRa = floor) else estimated

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) <= 1e-9, "expected $expected but was $actual")
    }

    @Test
    fun floorAboveTheEstimateLiftsTheControllerViewOnly() {
        val estimated = state(0.8)

        val forMpc = mpcView(estimated, floor = 2.5)

        assertNear(2.5, forMpc.estimatedRa)
        // The estimator's own state object is untouched, so nothing ratchets into the next tick.
        assertNear(0.8, estimated.estimatedRa)
    }

    @Test
    fun floorBelowTheEstimateChangesNothing() {
        val estimated = state(3.2)

        assertNear(3.2, mpcView(estimated, floor = 1.0).estimatedRa)
    }

    @Test
    fun noFloorMeansNoChange() {
        val estimated = state(1.4)

        assertNear(1.4, mpcView(estimated, floor = 0.0).estimatedRa)
    }

    @Test
    fun nonFiniteFloorIsIgnoredRatherThanPropagated() {
        val estimated = state(1.4)

        assertNear(1.4, mpcView(estimated, floor = Double.NaN).estimatedRa)
        assertNear(1.4, mpcView(estimated, floor = Double.POSITIVE_INFINITY).estimatedRa)
    }
}
