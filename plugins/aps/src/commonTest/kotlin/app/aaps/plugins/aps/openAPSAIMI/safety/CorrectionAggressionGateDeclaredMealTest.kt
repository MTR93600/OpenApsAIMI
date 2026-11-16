package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A low inside a declared meal is not a rebound once glucose is back at target.
 *
 * Replays the case of 2026-11-16: FCL declared at 12:46, a prebolus pushed glucose to 70 at 13:04,
 * the meal peaked at 206 at 13:44, FCL ended at 13:49. At 14:14 glucose was 158 and falling, target
 * 130, and the 75 min window still held the low (74.3, just under 75). One tick of rebound guard
 * followed, and it started a 45 min post-hypo tuning session.
 */
class CorrectionAggressionGateDeclaredMealTest {

    private fun input(
        bg: Double,
        targetBg: Double,
        delta: Double,
        minBg75: Double,
        declaredMealEpisode: Boolean,
        postHypo: CorrectionAggressionGate.PostHypoHint = CorrectionAggressionGate.PostHypoHint.NONE,
    ) = CorrectionAggressionGate.Input(
        bg = bg,
        targetBg = targetBg,
        deltaMgdl5m = delta,
        shortAvgDelta = delta,
        combinedDelta = delta,
        cob = 0.0,
        minBgLookback75m = minBg75,
        estimatedCarbs = 0.0,
        estimatedCarbsAgeMin = Double.MAX_VALUE,
        uamConfidence = 0.0,
        estimatedRa = 0.0,
        explicitMealMode = false,
        hasRecentMealEstimate = false,
        isConfirmedHighRise = false,
        postHypoHint = postHypo,
        declaredMealEpisode = declaredMealEpisode,
    )

    /** The 14:14 tick. */
    private fun tick1414(declared: Boolean) =
        input(bg = 158.5, targetBg = 130.0, delta = -9.4, minBg75 = 74.3, declaredMealEpisode = declared)

    @Test
    fun before_the_fix_the_1414_tick_was_a_rebound_guard() {
        assertEquals(
            CorrectionAggressionGate.Tier.REBOUND_GUARD,
            CorrectionAggressionGate.evaluate(tick1414(declared = false)).tier,
        )
    }

    @Test
    fun the_1414_tick_inside_a_declared_meal_is_moderate() {
        assertEquals(
            CorrectionAggressionGate.Tier.MODERATE,
            CorrectionAggressionGate.evaluate(tick1414(declared = true)).tier,
        )
    }

    @Test
    fun a_declared_meal_never_opens_the_full_tier() {
        val decision = CorrectionAggressionGate.evaluate(tick1414(declared = true))
        assertFalse(decision.mealTierFull)
        assertFalse(CorrectionAggressionGate.isMealTierFull(tick1414(declared = true)))
    }

    @Test
    fun refine_does_not_force_the_rebound_guard_inside_a_declared_meal() {
        val input = tick1414(declared = true).copy(
            postHypoHint = CorrectionAggressionGate.PostHypoHint.REBOUND_SUSPECTED,
        )
        val prior = CorrectionAggressionGate.evaluate(input)
        val refined = CorrectionAggressionGate.refineForPostHypo(
            prior,
            input,
            CorrectionAggressionGate.PostHypoHint.REBOUND_SUSPECTED,
        )
        assertEquals(CorrectionAggressionGate.Tier.MODERATE, refined.tier)
    }

    @Test
    fun under_target_the_guard_stays_even_inside_a_declared_meal() {
        // 13:09: 77 mg/dL, target 80, 5 min after the 70 low. The person was just low.
        val decision = CorrectionAggressionGate.evaluate(
            input(bg = 77.3, targetBg = 80.0, delta = 2.4, minBg75 = 69.6, declaredMealEpisode = true),
        )
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, decision.tier)
    }
}
