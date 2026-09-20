package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaGovernorTest {

    @Test
    fun blendsLearnedDiaWithProfile() {
        val r = DiaGovernor.resolve(
            profileDiaHours = 6.0,
            contextualDiaShiftHours = 0.0,
            pkpdLearnedDiaHours = 4.1,
            pkpdEnabled = true,
            governorEnabled = true,
            diaMinBound = 4.0,
            diaMaxBound = 9.0,
            learnedBlendWeight = 0.45,
        )
        assertTrue(r.appliedGovernor)
        // blended = anchor * (1 - w) + learned * w = 6.0 * 0.55 + 4.1 * 0.45 = 5.145
        // (l'attente historique de 5.05 correspondait à une moyenne 50/50, antérieure à la prise en
        // compte de learnedBlendWeight).
        assertEquals(5.145, r.effectiveDiaHours, 1e-6)
        assertEquals("LEARNED", r.dominantBranch)
    }

    @Test
    fun fallsBackToAnchorWhenDisabled() {
        val r = DiaGovernor.resolve(
            profileDiaHours = 6.0,
            contextualDiaShiftHours = 0.2,
            pkpdLearnedDiaHours = 4.0,
            pkpdEnabled = true,
            governorEnabled = false,
            diaMinBound = 4.0,
            diaMaxBound = 9.0,
            learnedBlendWeight = 0.5,
        )
        assertEquals(6.2, r.effectiveDiaHours, 0.01)
        assertEquals(false, r.appliedGovernor)
    }

    /**
     * The old rule compared |ctx| with |eff - profile|. Here |ctx| = 1.0 and |eff - profile| = 1.1,
     * so the old rule reported PRIOR even though the context part (0.9 h) is more than four times
     * the learned part (0.2 h). The new rule compares the two parts and reports CONTEXT.
     */
    @Test
    fun contextIsReportedWhenItsContributionIsTheLargest() {
        val r = DiaGovernor.resolve(
            profileDiaHours = 6.0,
            contextualDiaShiftHours = 1.0,
            pkpdLearnedDiaHours = 8.0,
            pkpdEnabled = true,
            governorEnabled = true,
            diaMinBound = 4.0,
            diaMaxBound = 12.0,
            learnedBlendWeight = 0.1,
        )
        assertEquals("CONTEXT", r.dominantBranch)
        // anchor = 7.0, eff = 7.0 * 0.9 + 8.0 * 0.1 = 7.1 — the same blend as before the label fix.
        assertEquals(7.1, r.effectiveDiaHours, 1e-9)
    }

    /** The learned part is still reported when it is the bigger one. */
    @Test
    fun learnedIsReportedWhenItsContributionIsTheLargest() {
        val r = DiaGovernor.resolve(
            profileDiaHours = 6.0,
            contextualDiaShiftHours = 0.2,
            pkpdLearnedDiaHours = 9.0,
            pkpdEnabled = true,
            governorEnabled = true,
            diaMinBound = 4.0,
            diaMaxBound = 12.0,
            learnedBlendWeight = 0.8,
        )
        assertEquals("LEARNED", r.dominantBranch)
        // anchor = 6.2, eff = 6.2 * 0.2 + 9.0 * 0.8 = 8.44
        assertEquals(8.44, r.effectiveDiaHours, 1e-9)
    }

    /**
     * The label fix must not move the number. Every case below is the plain blend
     * eff = (profile + ctx) * (1 - w) + learned * w, clamped to the bounds.
     */
    @Test
    fun effectiveDiaIsTheUnchangedBlendWhateverTheLabel() {
        data class Case(val ctx: Double, val learned: Double, val w: Double)

        val cases = listOf(
            Case(ctx = 0.0, learned = 4.1, w = 0.45),
            Case(ctx = 1.0, learned = 8.0, w = 0.1),
            Case(ctx = 0.2, learned = 9.0, w = 0.8),
            Case(ctx = -0.8, learned = 5.0, w = 0.5),
            Case(ctx = 0.9, learned = 6.0, w = 0.45),
        )
        for (case in cases) {
            val profile = 6.0
            val minBound = 4.0
            val maxBound = 12.0
            val r = DiaGovernor.resolve(
                profileDiaHours = profile,
                contextualDiaShiftHours = case.ctx,
                pkpdLearnedDiaHours = case.learned,
                pkpdEnabled = true,
                governorEnabled = true,
                diaMinBound = minBound,
                diaMaxBound = maxBound,
                learnedBlendWeight = case.w,
            )
            val anchor = (profile + case.ctx).coerceIn(minBound, maxBound)
            val expected = (anchor * (1.0 - case.w) + case.learned * case.w).coerceIn(minBound, maxBound)
            assertEquals(expected, r.effectiveDiaHours, 1e-9)
        }
    }

    /** A strong context with high confidence must now move the effective DIA in a visible way. */
    @Test
    fun strongContextMovesEffectiveDiaByAVisibleAmount() {
        val posterior = CausalStatePosterior(
            stressResistanceProb = 0.9,
            dominant = CausalStateId.STRESS_RESISTANCE,
            dominantConfidence = 0.9,
            learningQuality = 0.9,
        )
        val shift = CausalKineticsModulator.modulate(posterior).diaShiftHours
        val r = DiaGovernor.resolve(
            profileDiaHours = 6.0,
            contextualDiaShiftHours = shift,
            pkpdLearnedDiaHours = 6.0,
            pkpdEnabled = true,
            governorEnabled = true,
            diaMinBound = 4.0,
            diaMaxBound = 12.0,
            learnedBlendWeight = 0.45,
        )
        assertTrue(
            "stress context must move effective DIA by more than 0.4 h, got ${r.effectiveDiaHours}",
            r.effectiveDiaHours - 6.0 > 0.4
        )
        assertTrue(r.effectiveDiaHours <= 12.0)
    }

    /** Bounds still win over a big context shift. */
    @Test
    fun contextStaysInsideTheBounds() {
        val r = DiaGovernor.resolve(
            profileDiaHours = 7.5,
            contextualDiaShiftHours = 1.0,
            pkpdLearnedDiaHours = 7.9,
            pkpdEnabled = true,
            governorEnabled = true,
            diaMinBound = 5.0,
            diaMaxBound = 8.0,
            learnedBlendWeight = 0.45,
        )
        assertTrue(r.effectiveDiaHours in 5.0..8.0)
    }
}
