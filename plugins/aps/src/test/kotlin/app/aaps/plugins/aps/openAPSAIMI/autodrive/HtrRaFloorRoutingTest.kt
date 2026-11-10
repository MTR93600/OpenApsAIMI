package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The hyper-trajectory Ra floor lifts what the **controller** anticipates, never what the estimator
 * believes and never what the safety barrier judges against.
 *
 * The floor used to be passed as `AutoDriveState.estimatedRa` and dropped: `AutodriveEngine.tick`
 * overwrote the field with `stateEstimator.getLastRa()` before the estimator ran. It reached the
 * recursive belief tree as `mpcFeedForwardRa` but never the MPC its producer is named after.
 *
 * `ControlBarrierShield` reads `estimatedRa` to size its own margins (`ControlBarrierShield.kt:67`,
 * `101-103`, `115`); feeding it an optimistic value would stop it being a barrier. These assertions
 * pin the routing rule rather than the arithmetic.
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

    @Test
    fun `a floor above the estimate lifts the controller view only`() {
        val estimated = state(0.8)

        val forMpc = mpcView(estimated, floor = 2.5)

        assertThat(forMpc.estimatedRa).isWithin(1e-9).of(2.5)
        // The estimator's own state object is untouched, so nothing ratchets into the next tick.
        assertThat(estimated.estimatedRa).isWithin(1e-9).of(0.8)
    }

    @Test
    fun `a floor below the estimate changes nothing`() {
        val estimated = state(3.2)

        assertThat(mpcView(estimated, floor = 1.0).estimatedRa).isWithin(1e-9).of(3.2)
    }

    @Test
    fun `no floor means no change`() {
        val estimated = state(1.4)

        assertThat(mpcView(estimated, floor = 0.0).estimatedRa).isWithin(1e-9).of(1.4)
    }

    @Test
    fun `a non finite floor is ignored rather than propagated`() {
        val estimated = state(1.4)

        assertThat(mpcView(estimated, floor = Double.NaN).estimatedRa).isWithin(1e-9).of(1.4)
        assertThat(mpcView(estimated, floor = Double.POSITIVE_INFINITY).estimatedRa).isWithin(1e-9).of(1.4)
    }
}
