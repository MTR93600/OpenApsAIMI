package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The safety barrier must read a sensitivity that policy cannot lower.
 *
 * `enforce` derives `lgh = -siMetabolic * bg` and permits `safeU = (-gamma*h - lfh) / lgh`. A
 * **lower** sensitivity makes `|lgh|` smaller and the permitted dose **larger**, so anything able to
 * lower that number is able to loosen the barrier.
 *
 * `estimatedSI` is exactly such a number: it descends from `pkpdRuntime.fusedIsf`, which
 * `PkPdIntegration` multiplies by an aggression term bounded [0.55, 1.08] driven by delta, UAM
 * confidence and the behaviour family. On an undeclared meal that lowers the sensitivity by up to
 * 45 %, loosening the barrier and pushing the controller the same way at the same moment. A barrier
 * that moves with the controller is not a barrier.
 *
 * ## These numbers are the production regime
 *
 * Until `InsulinActionModel` landed, `AutoDriveState.createSafe` floored `estimatedSI` at `0.1` while
 * callers pass an ISF divided by 10000 — about 0.0045. Every patient was clamped to the same
 * sensitivity and the profile ISF never reached the barrier at all. The floor is gone and the
 * coefficient was re-derived so the *effective* insulin term is unchanged at ISF 45, which is why the
 * doses below are small: the barrier really does bind here, as it did in production.
 *
 * Measured on a 17 068-row `autodrive_dataset.csv` (2026-02-28 to 2026-07-11): on the 14 291 rows
 * where the floor was active the barrier intervened on 41.2 % of ticks and zeroed the whole dose on
 * 37.7 %. That is the behaviour these tests describe.
 */
class ControlBarrierShieldSafetySiTest {

    /** Fresh instance per call: `lastBgVelocity` is instance state and would change `activeGamma`. */
    private fun shield() = ControlBarrierShield(mockk<AAPSLogger>(relaxed = true))

    /** Profile sensitivity as carried on the state, i.e. ISF/10000. */
    private val profileAnchored = 45.0 / 10000.0

    /** The aggression term's lower bound in `PkPdIntegration`. */
    private val policyFloorMultiplier = 0.55

    /**
     * A state with real headroom: at BG 150, IOB 2 and Ra 1 the barrier permits about 2 U, so both a
     * loosening and a tightening are visible. Falling glucose keeps `strongMealRiseContext` off, so
     * `activeGamma` stays nominal.
     */
    private fun state(estimatedSI: Double) = AutoDriveState(
        bg = 150.0,
        bgVelocity = -1.0,
        iob = 2.0,
        estimatedSI = estimatedSI,
        estimatedRa = 1.0,
        physiologicalStressMask = DoubleArray(0),
        // High enough that the maxIOB truncation never masks what the barrier itself decided.
        maxIOB = 60.0,
    )

    /** Above the permitted dose in every arm, so the barrier is what limits the result. */
    private fun request() = AutoDriveCommand(
        scheduledMicroBolus = 6.0,
        temporaryBasalRate = 1.2,
        isSafe = true,
        reason = "test",
    )

    private fun permittedDose(estimatedSI: Double, safetySi: Double?): Double {
        val out = shield().enforce(request(), state(estimatedSI), profileBasal = 1.0, safetySi = safetySi)
        return out.scheduledMicroBolus + out.temporaryBasalRate / 12.0
    }

    @Test
    fun `a policy that lowers the commanded sensitivity no longer loosens the barrier`() {
        // Same tick, same physiology; only the aggression term differs.
        val nominal = permittedDose(estimatedSI = profileAnchored, safetySi = profileAnchored)
        val loweredByPolicy = permittedDose(
            estimatedSI = profileAnchored * policyFloorMultiplier, safetySi = profileAnchored
        )

        assertThat(loweredByPolicy).isWithin(1e-9).of(nominal)
    }

    /**
     * Two guards now stand between a policy multiplier and the barrier, and they cover different
     * ranges. `InsulinActionModel.controlCoefficient` floors at the pre-unification value, so nothing
     * can push the coefficient *below* where it was — that covers every profile ISF at or under the
     * reference. Above the reference the floor is inactive and only the anchor is left.
     */
    @Test
    fun `above the reference ISF, dropping the anchor lets the policy loosen the barrier`() {
        val sensitiveProfile = 70.0 / 10000.0

        val anchored = permittedDose(
            estimatedSI = sensitiveProfile * policyFloorMultiplier, safetySi = sensitiveProfile
        )
        val unanchored = permittedDose(
            estimatedSI = sensitiveProfile * policyFloorMultiplier, safetySi = null
        )

        assertThat(unanchored).isGreaterThan(anchored)
    }

    @Test
    fun `at or below the reference ISF the coefficient floor already blocks the loosening`() {
        val honest = permittedDose(estimatedSI = profileAnchored, safetySi = null)
        val loweredByPolicy = permittedDose(
            estimatedSI = profileAnchored * policyFloorMultiplier, safetySi = null
        )

        assertThat(loweredByPolicy).isWithin(1e-9).of(honest)
    }

    @Test
    fun `a defensive learner raising the sensitivity still tightens the barrier`() {
        // The anchor takes the more restrictive of the two, so a defensive rise must survive it.
        val nominal = permittedDose(estimatedSI = profileAnchored, safetySi = profileAnchored)
        val raised = permittedDose(
            estimatedSI = profileAnchored * 1.5, safetySi = profileAnchored * 1.5
        )

        assertThat(raised).isLessThan(nominal)
    }

    @Test
    fun `a missing anchor falls back to the commanded value rather than failing`() {
        val withAnchor = permittedDose(estimatedSI = profileAnchored, safetySi = profileAnchored)
        val withoutAnchor = permittedDose(estimatedSI = profileAnchored, safetySi = null)

        assertThat(withoutAnchor).isWithin(1e-9).of(withAnchor)
    }

    /**
     * The profile ISF reaches the barrier again.
     *
     * `createSafe` used to return `0.1` whatever was passed, so a resistant patient and a sensitive
     * one got an identical barrier. This is the regression test for that floor.
     */
    @Test
    fun `createSafe keeps the caller's sensitivity instead of flooring every patient at one value`() {
        val resistant = AutoDriveState.createSafe(
            bg = 150.0, bgVelocity = -1.0, iob = 2.0,
            estimatedSI = 20.0 / 10000.0, physiologicalStressMask = DoubleArray(0),
        )
        val sensitive = AutoDriveState.createSafe(
            bg = 150.0, bgVelocity = -1.0, iob = 2.0,
            estimatedSI = 80.0 / 10000.0, physiologicalStressMask = DoubleArray(0),
        )

        assertThat(resistant.estimatedSI).isWithin(1e-12).of(20.0 / 10000.0)
        assertThat(sensitive.estimatedSI).isWithin(1e-12).of(80.0 / 10000.0)
        // And a resistant patient is allowed more insulin than a sensitive one, as they should be.
        assertThat(permittedDose(resistant.estimatedSI, resistant.estimatedSI))
            .isGreaterThan(permittedDose(sensitive.estimatedSI, sensitive.estimatedSI))
    }

    /**
     * Pins the calibration that made unifying the three constants a no-op at the reference ISF.
     *
     * The barrier used to compute `estimatedSI * 0.05` against a sensitivity floored at 0.1, i.e. an
     * effective coefficient of 5.0e-3. If someone moves `MPC_TAU_MIN` for physiological reasons — 150
     * to 200 minutes is the honest range — this test fails, which is exactly when the barrier's
     * binding rate must be re-measured before shipping.
     */
    @Test
    fun `the unified model reproduces the previous effective coefficient at the reference ISF`() {
        val coefficient = InsulinActionModel.metabolicCoefficient(
            isfMgdlPerU = 45.0,
            tauMin = InsulinActionModel.MPC_TAU_MIN,
        )

        assertThat(coefficient).isWithin(1e-12).of(0.005)
    }

    /** Same pin for the estimator, whose term must not move until the Ra gates move with it. */
    @Test
    fun `the unified model reproduces the estimator's previous coefficient too`() {
        val coefficient = InsulinActionModel.metabolicCoefficient(
            isfMgdlPerU = 45.0,
            tauMin = InsulinActionModel.ESTIMATOR_TAU_MIN,
        )

        assertThat(coefficient).isWithin(1e-15).of(1.2e-4)
    }
}
