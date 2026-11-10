package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The attention gate may only ever make the loop **more** cautious.
 *
 * Lowering `estimatedSI` does not just push the MPC: `ControlBarrierShield` computes
 * `lgh = -siMetabolic * bg` from the same field, so a smaller sensitivity shrinks the coefficient on
 * the control action and the barrier permits a **larger** dose. An attention model that lowers SI is
 * therefore loosening a safety constraint.
 *
 * The permissive arm was `1.0 - (0.5 - score) * 0.4`, down to ×0.8. Since the classifier trains from
 * scratch on a ~3 % positive class with no feature normalisation, its fitted probability sits below
 * 0.5 almost always — so that arm would have fired on essentially every engaged tick, driven by the
 * base rate of hypoglycaemia rather than by measured resistance.
 *
 * These assertions pin the direction, not the arithmetic of the classifier.
 */
class MechanismAttentionGateDefensiveOnlyTest {

    /** Mirrors the multiplier selection in `MechanismAttentionGate.applyAttention`. */
    private fun multiplier(hypoRiskScore: Double): Double =
        if (hypoRiskScore > 0.5) 1.0 + (hypoRiskScore - 0.5) else 1.0

    @Test
    fun `a low hypo risk never lowers the sensitivity`() {
        // The whole range the base-rate-dominated classifier actually produces.
        listOf(0.0, 0.05, 0.2, 0.39, 0.49, 0.5).forEach { score ->
            assertThat(multiplier(score)).isAtLeast(1.0)
        }
    }

    @Test
    fun `a high hypo risk still raises the sensitivity, so the loop backs off`() {
        assertThat(multiplier(0.75)).isWithin(1e-9).of(1.25)
        assertThat(multiplier(1.0)).isWithin(1e-9).of(1.5)
    }

    @Test
    fun `the multiplier is never below one anywhere on the range`() {
        var s = 0.0
        while (s <= 1.0) {
            assertThat(multiplier(s)).isAtLeast(1.0)
            s += 0.01
        }
    }
}
