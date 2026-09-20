package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrectionAggressionGateTest {

    private fun input(
        bg: Double,
        targetBg: Double = 90.0,
        delta: Double = 0.0,
        shortAvg: Double = 0.0,
        combined: Double = 0.0,
        cob: Double = 0.0,
        minBg75: Double = 100.0,
        uam: Double = 0.0,
        ra: Double = 0.0,
        confirmedHighRise: Boolean = false,
        postHypo: CorrectionAggressionGate.PostHypoHint = CorrectionAggressionGate.PostHypoHint.NONE,
    ) = CorrectionAggressionGate.Input(
        bg = bg,
        targetBg = targetBg,
        deltaMgdl5m = delta,
        shortAvgDelta = shortAvg,
        combinedDelta = combined,
        cob = cob,
        minBgLookback75m = minBg75,
        estimatedCarbs = 0.0,
        estimatedCarbsAgeMin = Double.MAX_VALUE,
        uamConfidence = uam,
        estimatedRa = ra,
        explicitMealMode = false,
        hasRecentMealEstimate = false,
        isConfirmedHighRise = confirmedHighRise,
        postHypoHint = postHypo,
    )

    @Test
    fun userEpisode_17h44_reboundGuard_noHyperKicker() {
        val d = CorrectionAggressionGate.evaluate(
            input(bg = 93.0, delta = 19.0, combined = 4.0, minBg75 = 54.0, uam = 0.3)
        )
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, d.tier)
        assertFalse(d.allowGlobalHyperKicker)
        assertFalse(d.allowRocketHypoOverride)
        assertEquals(1.5, d.maxBasalScaleCap, 0.01)
    }

    @Test
    fun unannouncedMeal_highBgAndUam_fullTier() {
        val d = CorrectionAggressionGate.evaluate(
            input(bg = 145.0, delta = 6.0, combined = 1.5, minBg75 = 95.0, uam = 0.7)
        )
        assertEquals(CorrectionAggressionGate.Tier.FULL, d.tier)
        assertTrue(d.allowGlobalHyperKicker)
        assertTrue(d.mealTierFull)
    }

    @Test
    fun unannouncedMeal_sustainedRiseFromNormalBg_fullTier() {
        val d = CorrectionAggressionGate.evaluate(
            input(bg = 125.0, delta = 3.0, shortAvg = 3.0, combined = 1.2, minBg75 = 95.0, uam = 0.4)
        )
        assertEquals(CorrectionAggressionGate.Tier.FULL, d.tier)
        assertTrue(d.mealTierFull)
    }

    @Test
    fun cobMeal_fullTier() {
        val d = CorrectionAggressionGate.evaluate(input(bg = 130.0, delta = 4.0, cob = 5.0, minBg75 = 100.0))
        assertEquals(CorrectionAggressionGate.Tier.FULL, d.tier)
        assertTrue(d.allowGlobalHyperKicker)
    }

    @Test
    fun eveningHyper_plateau_fullTier() {
        val d = CorrectionAggressionGate.evaluate(
            input(bg = 192.0, delta = 3.0, combined = 0.9, minBg75 = 110.0)
        )
        assertEquals(CorrectionAggressionGate.Tier.FULL, d.tier)
        assertTrue(d.allowGlobalHyperKicker)
    }

    @Test
    fun moderateStressRise_moderateTier() {
        val d = CorrectionAggressionGate.evaluate(
            input(bg = 118.0, delta = 6.0, combined = 1.0, minBg75 = 100.0, uam = 0.2)
        )
        assertEquals(CorrectionAggressionGate.Tier.MODERATE, d.tier)
        assertTrue(d.allowGlobalHyperKicker)
        assertEquals(3.0, d.maxBasalScaleCap, 0.01)
    }

    @Test
    fun confirmedHighRise_fullTierAndRocketHypo() {
        val d = CorrectionAggressionGate.evaluate(
            input(bg = 165.0, delta = 8.0, minBg75 = 90.0, confirmedHighRise = true)
        )
        assertEquals(CorrectionAggressionGate.Tier.FULL, d.tier)
        assertTrue(d.allowRocketHypoOverride)
    }

    @Test
    fun rebound_18h59_guard_noRocketHypo() {
        val d = CorrectionAggressionGate.evaluate(
            input(bg = 74.0, delta = 8.0, combined = 2.0, minBg75 = 57.0, uam = 0.3)
        )
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, d.tier)
        assertFalse(d.allowRocketHypoOverride)
        assertFalse(d.allowGlobalHyperKicker)
    }

    @Test
    fun refinePostHypoRebound_downgradesModerateToGuard() {
        val base = CorrectionAggressionGate.evaluate(
            input(bg = 125.0, targetBg = 100.0, delta = 6.0, combined = 1.2, minBg75 = 80.0)
        )
        assertEquals(CorrectionAggressionGate.Tier.MODERATE, base.tier)
        val refined = CorrectionAggressionGate.refineForPostHypo(
            base,
            input(bg = 125.0, targetBg = 100.0, delta = 6.0, minBg75 = 65.0),
            CorrectionAggressionGate.PostHypoHint.REBOUND_SUSPECTED,
        )
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, refined.tier)
    }

    @Test
    fun thomasIncident_reboundSteepRise_staysReboundGuard() {
        val d = CorrectionAggressionGate.evaluate(
            input(
                bg = 119.0,
                targetBg = 100.0,
                delta = 5.0,
                combined = 2.5,
                minBg75 = 54.0,
                uam = 0.7,
                ra = 0.9,
            ),
        )
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, d.tier)
        assertFalse(d.allowRocketBasalScale)
        assertEquals(1.5, d.maxBasalScaleCap, 0.01)
    }

    @Test
    fun refinePostHypoRebound_overridesFullWhenOnlyRiseDerived() {
        val base = CorrectionAggressionGate.evaluate(
            input(bg = 145.0, targetBg = 100.0, delta = 6.0, combined = 1.5, minBg75 = 95.0, uam = 0.7),
        )
        assertEquals(CorrectionAggressionGate.Tier.FULL, base.tier)
        val refined = CorrectionAggressionGate.refineForPostHypo(
            base,
            input(bg = 119.0, targetBg = 100.0, delta = 5.0, combined = 2.5, minBg75 = 54.0, uam = 0.7, ra = 0.9),
            CorrectionAggressionGate.PostHypoHint.REBOUND_SUSPECTED,
        )
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, refined.tier)
        assertFalse(refined.mealTierFull)
    }

    @Test
    fun postHypoStrictMeal_uamConfidence() {
        assertTrue(
            CorrectionAggressionGate.isMealLikelyPostHypoStrict(
                cob = 0.0,
                estimatedCarbs = 0.0,
                estimatedCarbsAgeMs = 0L,
                uamConfidence = 0.7,
                bg = 100.0,
                shortAvgDelta = 0f,
                delta = 0f,
                recentBGs = emptyList(),
            )
        )
    }

    @Test
    fun postHypoStrictMeal_rejectsDeltaAndHourOnly() {
        assertFalse(
            CorrectionAggressionGate.isMealLikelyPostHypoStrict(
                cob = 0.0,
                estimatedCarbs = 0.0,
                estimatedCarbsAgeMs = 0L,
                uamConfidence = 0.2,
                bg = 110.0,
                shortAvgDelta = 4f,
                delta = 3f,
                recentBGs = listOf(100f, 105f, 110f),
            )
        )
    }
}
