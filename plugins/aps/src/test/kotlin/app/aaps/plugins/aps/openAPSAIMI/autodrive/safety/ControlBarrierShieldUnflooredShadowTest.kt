package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The unfloored counterfactual must really remove the floor.
 *
 * `AutodriveEngine.recordCoefficientShadow` exports `cbf_permitted_unfloored_u` to answer one
 * question: what does `InsulinActionModel.LEGACY_CONTROL_COEFFICIENT` cost? Its first version tried
 * to express "no floor" as a sensitivity:
 *
 * ```
 * unflooredEquivalentSi = coefUnfloored * REFERENCE_BG_MGDL * MPC_TAU_MIN / 10000
 * ```
 *
 * which reduces to `isf / 10000`, i.e. exactly the `safetySi` production already passes. `enforce`
 * then sent it back through `controlCoefficient`, which re-applied the floor. The counterfactual was
 * therefore an algebraic no-op, and the export showed `cbf_permitted_unfloored_u == cbf_permitted_u`
 * on 281 of 281 ticks on the 2026-09-05 night — read as "the floor cost nothing".
 *
 * The first test below writes down that no-op. It was green before the fix and stays green after,
 * because the arithmetic it describes has not changed: it is the reason the shadow needed a new
 * entry point, not a bug in the shield. The second test is the lock, and it was red before the fix.
 */
class ControlBarrierShieldUnflooredShadowTest {

    /** Fresh instance per call: `lastBgVelocity` is instance state and would change `activeGamma`. */
    private fun shield() = ControlBarrierShield(mockk<AAPSLogger>(relaxed = true))

    /**
     * A profile ISF of 30 mg/dL/U, well under the reference 45, so the floor is active.
     *
     * At ISF 30 the honest coefficient is `30 / (75 * 120)` = 3.33e-3 and the floor holds it at
     * 5.0e-3, i.e. the barrier behaves as if the patient were 50 % more insulin sensitive than they
     * are. 30 is the median profile ISF on this deployment.
     */
    private val resistantIsf = 30.0

    private val safetySi = resistantIsf / 10000.0

    /** Falling glucose with real headroom, so the barrier binds and the difference is visible. */
    private fun state() = AutoDriveState(
        bg = 150.0,
        bgVelocity = -1.0,
        iob = 2.0,
        estimatedSI = safetySi,
        estimatedRa = 1.0,
        physiologicalStressMask = DoubleArray(0),
        maxIOB = 60.0,
    )

    /** More than the barrier can ever permit here, so the barrier is what limits the result. */
    private fun request() = AutoDriveCommand(
        scheduledMicroBolus = 6.0,
        temporaryBasalRate = 1.2,
        isSafe = true,
        reason = "test",
    )

    private fun AutoDriveCommand.totalU() = scheduledMicroBolus + temporaryBasalRate / 12.0

    private fun production() =
        shield().enforce(request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 1L)

    /**
     * The old recipe could not remove the floor, whatever number it computed.
     *
     * This is not a defect of the shield: any sensitivity handed to `enforce` goes through
     * `controlCoefficient`, so the floor comes back. It is why the shadow now passes the coefficient
     * itself.
     */
    @Test
    fun `expressing the unfloored coefficient as a sensitivity reproduces the production dose`() {
        val unflooredCoefficient =
            InsulinActionModel.metabolicCoefficient(resistantIsf, InsulinActionModel.MPC_TAU_MIN)
        val oldRecipeSi = unflooredCoefficient * InsulinActionModel.REFERENCE_BG_MGDL *
            InsulinActionModel.MPC_TAU_MIN / 10000.0

        // Step one: the "unfloored equivalent sensitivity" is the sensitivity we already had.
        assertThat(oldRecipeSi).isWithin(1e-15).of(safetySi)

        // Step two: so the shadow command is the production command, to the last digit.
        val shadow = shield()
            .enforce(request(), state(), profileBasal = 1.0, safetySi = oldRecipeSi, observationId = 1L)
        assertThat(shadow.totalU()).isWithin(1e-12).of(production().totalU())
    }

    /**
     * With the coefficient passed straight in, the floor's cost becomes visible.
     *
     * This is the lock. Before the fix the two numbers were equal on every tick; the floor holds the
     * coefficient 50 % above the honest value at ISF 30, which shrinks the permitted dose by the
     * same kind of factor.
     */
    @Test
    fun `passing the unfloored coefficient permits strictly more than the floored barrier`() {
        val unflooredCoefficient =
            InsulinActionModel.metabolicCoefficient(resistantIsf, InsulinActionModel.MPC_TAU_MIN)
        assertThat(unflooredCoefficient).isLessThan(InsulinActionModel.LEGACY_CONTROL_COEFFICIENT)

        val unfloored = shield().enforce(
            request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 1L,
            siMetabolicOverride = unflooredCoefficient,
        )

        assertThat(unfloored.totalU()).isGreaterThan(production().totalU())
    }

    /**
     * A shadow run must leave nothing behind that a later production run could read.
     *
     * `enforce` is pure apart from `lastBgVelocity`, which is keyed on the observation id. Two
     * shields see the same production tick; only one of them also runs the shadow. The next
     * production tick must be identical on both.
     */
    @Test
    fun `running the shadow does not change the next production command`() {
        val clean = shield()
        val shadowed = shield()

        clean.enforce(request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 1L)
        shadowed.enforce(request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 1L)
        shadowed.enforce(
            request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 1L,
            siMetabolicOverride = InsulinActionModel
                .metabolicCoefficient(resistantIsf, InsulinActionModel.MPC_TAU_MIN),
        )

        // Second observation, so the acceleration memory of the first one is what is being read.
        val nextClean =
            clean.enforce(request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 2L)
        val nextShadowed =
            shadowed.enforce(request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 2L)

        assertThat(nextShadowed.totalU()).isWithin(1e-12).of(nextClean.totalU())
        assertThat(nextShadowed.temporaryBasalRate).isWithin(1e-12).of(nextClean.temporaryBasalRate)
        assertThat(nextShadowed.scheduledMicroBolus).isWithin(1e-12).of(nextClean.scheduledMicroBolus)
    }

    /** A broken shadow value must never widen the barrier: it falls back to the normal path. */
    @Test
    fun `a non-finite or non-positive override is ignored`() {
        val expected = production().totalU()

        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            val out = shield().enforce(
                request(), state(), profileBasal = 1.0, safetySi = safetySi, observationId = 1L,
                siMetabolicOverride = bad,
            )
            assertThat(out.totalU()).isWithin(1e-12).of(expected)
        }
    }
}
