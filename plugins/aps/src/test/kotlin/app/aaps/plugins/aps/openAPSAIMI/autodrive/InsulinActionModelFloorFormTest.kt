package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.LEGACY_CONTROL_COEFFICIENT
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.MIN_FLOOR_ISF_MGDL_PER_U
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.MPC_TAU_MIN
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.controlCoefficient
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.metabolicCoefficient
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The floor moved from a coefficient to a sensitivity, and no dose may move with it.
 *
 * The old form was `max(metabolicCoefficient(isf, tau), 0.005)`. The new one is
 * `max(metabolicCoefficient(isf, tau), metabolicCoefficient(45, tau))`, with the constant kept only as
 * a last resort. At [MPC_TAU_MIN] the two agree exactly, because `45 / (75 * 120)` is the same double
 * as `0.005`. These tests hold that equality and, just as important, hold the edge cases where the
 * rewrite could quietly turn a floor into a zero.
 */
class InsulinActionModelFloorFormTest {

    /** The old expression, kept here so the comparison is against real code and not against a memory. */
    private fun oldForm(isf: Double, tau: Double): Double =
        maxOf(metabolicCoefficient(isf, tau), LEGACY_CONTROL_COEFFICIENT)

    private val sensitivities = listOf(
        1.0, 5.0, 9.0, 25.0, 27.0, 30.0, 35.0, 44.999, 45.0, 45.001,
        50.0, 60.0, 90.0, 120.0, 400.0, 1e9, 0.0, -5.0,
        Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
    )

    @Test
    fun `at the production tau the new form is the old one, bit for bit`() {
        sensitivities.forEach { isf ->
            assertThat(controlCoefficient(isf, MPC_TAU_MIN))
                .isEqualTo(oldForm(isf, MPC_TAU_MIN))
        }
    }

    /** The equality above rests on this one exact division. If it ever drifts, everything else does. */
    @Test
    fun `the floor sensitivity divides into exactly the legacy coefficient`() {
        assertThat(MIN_FLOOR_ISF_MGDL_PER_U / (MPC_TAU_MIN * 120.0)).isEqualTo(LEGACY_CONTROL_COEFFICIENT)
        assertThat(metabolicCoefficient(MIN_FLOOR_ISF_MGDL_PER_U, MPC_TAU_MIN)).isEqualTo(LEGACY_CONTROL_COEFFICIENT)
    }

    /**
     * A zero coefficient would set `lgh` to zero and send `safeU` to infinity: the barrier would not
     * tighten, it would disappear. No input may produce one.
     */
    @Test
    fun `no input can drive the coefficient to zero`() {
        val taus = listOf(MPC_TAU_MIN, 150.0, 200.0, 1.0, 0.0, -1.0, Double.NaN)
        taus.forEach { tau ->
            sensitivities.forEach { isf ->
                assertThat(controlCoefficient(isf, tau)).isGreaterThan(0.0)
            }
        }
    }

    /** A sensitivity that cannot be read must not loosen the barrier below the floor. */
    @Test
    fun `an unusable sensitivity still gets the floor`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -5.0).forEach { isf ->
            assertThat(controlCoefficient(isf, MPC_TAU_MIN)).isEqualTo(LEGACY_CONTROL_COEFFICIENT)
        }
    }

    /** A tau that cannot be used falls back to the constant rather than to nothing. */
    @Test
    fun `an unusable tau falls back to the legacy coefficient`() {
        listOf(0.0, -1.0, Double.NaN).forEach { tau ->
            assertThat(controlCoefficient(45.0, tau)).isEqualTo(LEGACY_CONTROL_COEFFICIENT)
        }
    }

    /**
     * The point of the rewrite: tau now reaches the floored ticks too.
     *
     * In the old form a tick at ISF 30 answered 0.005 at every tau, because the floor was a constant.
     * The switch point moved with tau instead of the value, so raising tau changed nothing exactly
     * where the floor was already active.
     */
    @Test
    fun `the floor now follows tau instead of swallowing it`() {
        val resistant = 30.0
        assertThat(oldForm(resistant, 75.0)).isEqualTo(oldForm(resistant, 150.0))

        assertThat(controlCoefficient(resistant, 150.0)).isLessThan(controlCoefficient(resistant, 75.0))
        assertThat(controlCoefficient(resistant, 150.0))
            .isWithin(1e-15).of(controlCoefficient(resistant, 75.0) / 2.0)
    }

    /** Whatever tau is, a sensitivity under the floor is treated as the floor and never below it. */
    @Test
    fun `the switch point is the sensitivity, not a coefficient that depends on tau`() {
        listOf(75.0, 150.0, 200.0).forEach { tau ->
            assertThat(controlCoefficient(30.0, tau)).isEqualTo(controlCoefficient(MIN_FLOOR_ISF_MGDL_PER_U, tau))
            assertThat(controlCoefficient(60.0, tau)).isGreaterThan(controlCoefficient(MIN_FLOOR_ISF_MGDL_PER_U, tau))
        }
    }
}
