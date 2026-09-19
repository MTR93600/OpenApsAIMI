package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.LEGACY_CONTROL_COEFFICIENT
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.MIN_FLOOR_ISF_MGDL_PER_U
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.MPC_TAU_MIN
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.controlCoefficient
import app.aaps.plugins.aps.openAPSAIMI.autodrive.InsulinActionModel.metabolicCoefficient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The floor moved from a coefficient to a sensitivity, and no dose may move with it.
 *
 * The old form was `max(metabolicCoefficient(isf, tau), 0.005)`. The new one is
 * `max(metabolicCoefficient(isf, tau), metabolicCoefficient(45, tau))`, with the constant kept only as
 * a last resort. At [MPC_TAU_MIN] the two agree exactly, because `45 / (75 * 120)` is the same double
 * as `0.005`. These tests hold that equality and, just as important, hold the edge cases where the
 * rewrite could quietly turn a floor into a zero.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `f519320841`. Study source set: [InsulinActionModel]
 * is commonMain, so the tests live in `commonTest` (`kotlin.test`) — not `androidHostTest`
 * (Truth / JUnit / mockk). Backtick names omit commas (Kotlin/Native).
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
    fun `at the production tau the new form is the old one bit for bit`() {
        sensitivities.forEach { isf ->
            val expected = oldForm(isf, MPC_TAU_MIN)
            val actual = controlCoefficient(isf, MPC_TAU_MIN)
            assertEquals(
                expected.toRawBits(),
                actual.toRawBits(),
                "isf=$isf expected=$expected actual=$actual",
            )
        }
    }

    /** The equality above rests on this one exact division. If it ever drifts, everything else does. */
    @Test
    fun `the floor sensitivity divides into exactly the legacy coefficient`() {
        val writtenAsSensitivity = MIN_FLOOR_ISF_MGDL_PER_U / (MPC_TAU_MIN * 120.0)
        assertEquals(
            LEGACY_CONTROL_COEFFICIENT.toRawBits(),
            writtenAsSensitivity.toRawBits(),
            "45/(75*120)=$writtenAsSensitivity vs LEGACY=$LEGACY_CONTROL_COEFFICIENT",
        )
        assertEquals(
            LEGACY_CONTROL_COEFFICIENT.toRawBits(),
            metabolicCoefficient(MIN_FLOOR_ISF_MGDL_PER_U, MPC_TAU_MIN).toRawBits(),
        )
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
                assertTrue(
                    controlCoefficient(isf, tau) > 0.0,
                    "isf=$isf tau=$tau produced ${controlCoefficient(isf, tau)}",
                )
            }
        }
    }

    /** A sensitivity that cannot be read must not loosen the barrier below the floor. */
    @Test
    fun `an unusable sensitivity still gets the floor`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -5.0).forEach { isf ->
            assertEquals(LEGACY_CONTROL_COEFFICIENT, controlCoefficient(isf, MPC_TAU_MIN), 0.0)
        }
    }

    /** A tau that cannot be used falls back to the constant rather than to nothing. */
    @Test
    fun `an unusable tau falls back to the legacy coefficient`() {
        listOf(0.0, -1.0, Double.NaN).forEach { tau ->
            assertEquals(LEGACY_CONTROL_COEFFICIENT, controlCoefficient(45.0, tau), 0.0)
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
        assertEquals(oldForm(resistant, 75.0), oldForm(resistant, 150.0), 0.0)

        assertTrue(
            controlCoefficient(resistant, 150.0) < controlCoefficient(resistant, 75.0),
            "floored coefficient at tau=150 should be smaller than at tau=75",
        )
        assertEquals(
            controlCoefficient(resistant, 75.0) / 2.0,
            controlCoefficient(resistant, 150.0),
            1e-15,
        )
    }

    /** Whatever tau is, a sensitivity under the floor is treated as the floor and never below it. */
    @Test
    fun `the switch point is the sensitivity not a coefficient that depends on tau`() {
        listOf(75.0, 150.0, 200.0).forEach { tau ->
            assertEquals(
                controlCoefficient(MIN_FLOOR_ISF_MGDL_PER_U, tau),
                controlCoefficient(30.0, tau),
                0.0,
            )
            assertTrue(
                controlCoefficient(60.0, tau) > controlCoefficient(MIN_FLOOR_ISF_MGDL_PER_U, tau),
                "isf=60 at tau=$tau should sit above the floor",
            )
        }
    }
}
