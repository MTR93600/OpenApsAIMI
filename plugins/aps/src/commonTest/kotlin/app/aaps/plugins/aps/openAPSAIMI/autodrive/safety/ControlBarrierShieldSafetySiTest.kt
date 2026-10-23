package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The safety barrier must read a sensitivity that policy cannot lower.
 *
 * `enforce` derives `lgh = -siMetabolic * bg` and permits `safeU = (-gamma*h - lfh) / lgh`. A
 * **lower** sensitivity makes `|lgh|` smaller and the permitted dose **larger**, so anything able to
 * lower that number is able to loosen the barrier. `estimatedSI` is such a number: it comes from
 * `pkpdRuntime.fusedIsf`, which `PkPdIntegration` multiplies by an aggression term bounded
 * [0.55, 1.08]. A barrier that moves with the controller is not a barrier.
 *
 * Port of `ControlBarrierShieldSafetySiTest` from `dev_OAPSAIMI` (plugins/aps/src/test).
 * [ControlBarrierShield], [InsulinActionModel] and `AutoDriveState.createSafe` are commonMain and
 * match dev (only log tags and number formatting differ), so the test lives in `commonTest`.
 * Adapted: the mockk logger is replaced by [NoOpAapsLogger], Truth by `kotlin.test`, backtick names
 * by camelCase.
 */
class ControlBarrierShieldSafetySiTest {

    /** Fresh instance per call: `lastBgVelocity` is instance state and would change `activeGamma`. */
    private fun shield() = ControlBarrierShield(NoOpAapsLogger)

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
        // High enough that the maxIOB cut never hides what the barrier itself decided.
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

    private fun assertNear(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun policyThatLowersTheCommandedSensitivityNoLongerLoosensTheBarrier() {
        // Same tick, same physiology; only the aggression term differs.
        val nominal = permittedDose(estimatedSI = profileAnchored, safetySi = profileAnchored)
        val loweredByPolicy = permittedDose(
            estimatedSI = profileAnchored * policyFloorMultiplier, safetySi = profileAnchored,
        )

        assertNear(nominal, loweredByPolicy, 1e-9)
    }

    /**
     * Two guards stand between a policy multiplier and the barrier, and they cover different ranges.
     * `InsulinActionModel.controlCoefficient` floors at the old value, which covers every profile ISF
     * at or under the reference. Above the reference the floor is inactive and only the anchor is left.
     */
    @Test
    fun aboveTheReferenceIsfDroppingTheAnchorLetsThePolicyLoosenTheBarrier() {
        val sensitiveProfile = 70.0 / 10000.0

        val anchored = permittedDose(
            estimatedSI = sensitiveProfile * policyFloorMultiplier, safetySi = sensitiveProfile,
        )
        val unanchored = permittedDose(
            estimatedSI = sensitiveProfile * policyFloorMultiplier, safetySi = null,
        )

        assertTrue(unanchored > anchored, "unanchored=$unanchored anchored=$anchored")
    }

    @Test
    fun atOrBelowTheReferenceIsfTheCoefficientFloorAlreadyBlocksTheLoosening() {
        val honest = permittedDose(estimatedSI = profileAnchored, safetySi = null)
        val loweredByPolicy = permittedDose(
            estimatedSI = profileAnchored * policyFloorMultiplier, safetySi = null,
        )

        assertNear(honest, loweredByPolicy, 1e-9)
    }

    @Test
    fun defensiveLearnerRaisingTheSensitivityStillTightensTheBarrier() {
        // The anchor takes the more restrictive of the two, so a defensive rise must survive it.
        val nominal = permittedDose(estimatedSI = profileAnchored, safetySi = profileAnchored)
        val raised = permittedDose(
            estimatedSI = profileAnchored * 1.5, safetySi = profileAnchored * 1.5,
        )

        assertTrue(raised < nominal, "raised=$raised nominal=$nominal")
    }

    @Test
    fun missingAnchorFallsBackToTheCommandedValueRatherThanFailing() {
        val withAnchor = permittedDose(estimatedSI = profileAnchored, safetySi = profileAnchored)
        val withoutAnchor = permittedDose(estimatedSI = profileAnchored, safetySi = null)

        assertNear(withAnchor, withoutAnchor, 1e-9)
    }

    /**
     * The profile ISF reaches the barrier again.
     *
     * `createSafe` used to return `0.1` whatever was passed, so a resistant patient and a sensitive
     * one got the same barrier. This is the regression test for that floor.
     */
    @Test
    fun createSafeKeepsTheCallerSensitivityInsteadOfFlooringEveryPatientAtOneValue() {
        val resistant = AutoDriveState.createSafe(
            bg = 150.0, bgVelocity = -1.0, iob = 2.0,
            estimatedSI = 20.0 / 10000.0, physiologicalStressMask = DoubleArray(0),
        )
        val sensitive = AutoDriveState.createSafe(
            bg = 150.0, bgVelocity = -1.0, iob = 2.0,
            estimatedSI = 80.0 / 10000.0, physiologicalStressMask = DoubleArray(0),
        )

        assertNear(20.0 / 10000.0, resistant.estimatedSI, 1e-12)
        assertNear(80.0 / 10000.0, sensitive.estimatedSI, 1e-12)
        // And a resistant patient is allowed more insulin than a sensitive one, as they should be.
        val resistantDose = permittedDose(resistant.estimatedSI, resistant.estimatedSI)
        val sensitiveDose = permittedDose(sensitive.estimatedSI, sensitive.estimatedSI)
        assertTrue(resistantDose > sensitiveDose, "resistant=$resistantDose sensitive=$sensitiveDose")
    }

    /**
     * Pins the calibration that made unifying the three constants a no-op at the reference ISF.
     * If someone moves `MPC_TAU_MIN`, this fails, which is exactly when the barrier's binding rate
     * must be measured again before shipping.
     */
    @Test
    fun unifiedModelReproducesThePreviousEffectiveCoefficientAtTheReferenceIsf() {
        val coefficient = InsulinActionModel.metabolicCoefficient(
            isfMgdlPerU = 45.0,
            tauMin = InsulinActionModel.MPC_TAU_MIN,
        )

        assertNear(0.005, coefficient, 1e-12)
    }

    /** Same pin for the estimator, whose term must not move until the Ra gates move with it. */
    @Test
    fun unifiedModelReproducesTheEstimatorPreviousCoefficientToo() {
        val coefficient = InsulinActionModel.metabolicCoefficient(
            isfMgdlPerU = 45.0,
            tauMin = InsulinActionModel.ESTIMATOR_TAU_MIN,
        )

        assertNear(1.2e-4, coefficient, 1e-15)
    }
}
