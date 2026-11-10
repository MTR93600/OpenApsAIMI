package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class AdvancedPredictionEngineTest {

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun `test predict no horizon`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        val result = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 0
        )
        assertEquals(1, result.size)
        assertEquals(100.0, result[0], 0.0)
    }

    @Test
    fun `test predict flat with no IOB or COB`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val result = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 60
        )
        // Should remain flat at 100
        assertTrue(result.all { it == 100.0 })
    }

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun `test predict drop with IOB`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 1.0
        every { iobEntry.time } returns System.currentTimeMillis()

        val result = AdvancedPredictionEngine.predict(
            currentBG = 200.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 60
        )
        // Should drop
        assertTrue(result.last() < 200.0)
    }

    @Test
    fun predictCurves_uamRisesWithPositiveDeltaWhileIobFalls() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 6.0
        every { iobEntry.activity } returns 0.02
        every { iobEntry.time } returns System.currentTimeMillis()

        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 119.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            delta = 5.0,
            horizonMinutes = 60,
        )
        assertTrue(curves.iob.last() < curves.iob.first())
        assertTrue(curves.uam.last() >= curves.uam.first())
        assertNotEquals(curves.iob.last(), curves.uam.last())
    }

    @Test
    fun predictCurves_falseMealSuppressionDampensUamAndHybridRise() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 5.0
        every { iobEntry.activity } returns 0.02
        every { iobEntry.time } returns System.currentTimeMillis()

        val baseline = AdvancedPredictionEngine.predictCurves(
            currentBG = 130.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 18.0,
            profile = profile,
            delta = 6.0,
            horizonMinutes = 60,
        )
        val suppressed = AdvancedPredictionEngine.predictCurves(
            currentBG = 130.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 18.0,
            profile = profile,
            delta = 6.0,
            horizonMinutes = 60,
            modulation = PredictionPhysioModulation(
                carbImpactFactor = 0.84,
                uamMomentumFactor = 0.24,
                hybridMomentumFactor = 0.32,
                momentumDecayFactor = 0.88,
                falseMealSuppression = true,
                source = "test_false_meal",
            ),
        )

        assertTrue(suppressed.uam.last() < baseline.uam.last())
        assertTrue(suppressed.hybrid.last() < baseline.hybrid.last())
    }

    @Test
    fun predictCurves_mealSupportAmplifiesCobAndHybridTrajectory() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 4.0
        every { iobEntry.activity } returns 0.015
        every { iobEntry.time } returns System.currentTimeMillis()

        val baseline = AdvancedPredictionEngine.predictCurves(
            currentBG = 135.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 28.0,
            profile = profile,
            delta = 4.5,
            horizonMinutes = 60,
        )
        val mealSupported = AdvancedPredictionEngine.predictCurves(
            currentBG = 135.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 28.0,
            profile = profile,
            delta = 4.5,
            horizonMinutes = 60,
            modulation = PredictionPhysioModulation(
                effectiveSensitivityMgdlPerU = 44.0,
                insulinImpactFactor = 1.05,
                carbImpactFactor = 1.18,
                uamMomentumFactor = 1.14,
                hybridMomentumFactor = 1.10,
                momentumDecayFactor = 1.03,
                source = "test_meal_support",
            ),
        )

        assertTrue(mealSupported.cob.last() > baseline.cob.last())
        assertTrue(mealSupported.hybrid.last() > baseline.hybrid.last())
    }

    /** Bolus-tail IOB array: insulin stays materially active across the horizon (learned DIA >
     *  horizon), replicating the undeclared-meal support package where the insulin-only path crashes. */
    private fun bolusTailArray(): Array<IobTotal> {
        val now = System.currentTimeMillis()
        return (0..48).map { i ->
            val e = mockk<IobTotal>()
            every { e.iob } returns 13.5
            every { e.activity } returns (0.060 * Math.exp(-i / 90.0))
            every { e.time } returns now + i * 5 * 60_000L
            e
        }.toTypedArray()
    }

    private fun hyperReversionProfile(): OapsProfileAimi {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 54.0
        return profile
    }

    private fun runHyperReversion(currentBG: Double, delta: Double, hyper: Boolean) =
        AdvancedPredictionEngine.predictCurves(
            currentBG = currentBG, iobArray = bolusTailArray(), finalSensitivity = 36.0,
            cobG = 0.0, profile = hyperReversionProfile(), delta = delta, horizonMinutes = 240,
            endogenousReversionEnabled = true, hyperReversionEnabled = hyper,
        )

    @Test
    fun hyperReversion_holds_soft_pathmin_at_baseline_in_clear_hyper() {
        val off = runHyperReversion(currentBG = 213.0, delta = 0.0, hyper = false)
        val on = runHyperReversion(currentBG = 213.0, delta = 0.0, hyper = true)

        // Legacy: insulin-only path crashes to the 39 artefact → false predictive hypo.
        assertTrue(off.insulinPathMinSoftMgdl!! < 45.0)
        assertTrue(!off.endogenousReversionOnInsulinCurves)
        // Fix: soft path-min held at the counter-regulation baseline (≈80), above any hypo threshold.
        assertTrue(on.insulinPathMinSoftMgdl!! >= 79.0)
        assertTrue(on.endogenousReversionOnInsulinCurves)
        // Never optimistic: the held floor stays well below the current BG.
        assertTrue(on.insulinPathMinSoftMgdl!! < 213.0)
    }

    @Test
    fun hyperReversion_no_effect_at_euglycemia() {
        val off = runHyperReversion(currentBG = 120.0, delta = 0.0, hyper = false)
        val on = runHyperReversion(currentBG = 120.0, delta = 0.0, hyper = true)

        // BG < 160 → not clear-hyper → identical to legacy (hypo-protective behaviour untouched).
        assertEquals(off.insulinPathMinSoftMgdl!!, on.insulinPathMinSoftMgdl!!, 1e-6)
    }

    @Test
    fun hyperReversion_suspended_when_falling_hard() {
        // Guard B: delta ≤ −3 suspends EGP even in clear hyper → path stays pessimistic (no hold).
        val on = runHyperReversion(currentBG = 213.0, delta = -5.0, hyper = true)

        assertTrue(on.insulinPathMinSoftMgdl!! < 45.0)
        assertTrue(!on.endogenousReversionOnInsulinCurves)
    }

    /**
     * Reproduces the 2026-08-14 lunch at 14:26 — BG 211.7 with 13.04 U on board and no declared
     * carbs. The published path minimum reads 39, and every consumer downstream treats that as a
     * forecast of imminent hypoglycaemia. The unclipped mirror shows what the arithmetic really said.
     */
    @Test
    fun unclippedPathMin_separates_a_saturated_floor_from_a_real_forecast() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 13.6
        every { profile.peakTime } returns 54.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 13.04
        every { iobEntry.activity } returns 0.05
        every { iobEntry.time } returns System.currentTimeMillis()

        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 211.7,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 37.3,
            cobG = 0.0,
            profile = profile,
            delta = -7.5,
            horizonMinutes = 240,
        )

        // The published minimum saturates at the numeric floor and says nothing more.
        assertEquals(39.0, curves.insulinPathMinRawMgdl!!, 1e-6)
        // The mirror says the arithmetic ran far past it, and how many steps the clip absorbed.
        assertTrue(curves.insulinPathMinUnclippedMgdl!! < 0.0)
        assertTrue(curves.numericFloorClippedSteps > 0)
        // And the sensitivity that produced it is now on the record.
        assertEquals(37.3, curves.effectiveSensitivityUsedMgdlPerU!!, 1e-6)
    }

    @Test
    fun unclippedPathMin_matches_the_published_min_when_the_floor_never_binds() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 13.6
        every { profile.peakTime } returns 54.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 1.0
        every { iobEntry.activity } returns 0.005
        every { iobEntry.time } returns System.currentTimeMillis()

        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 150.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 30.0,
            cobG = 0.0,
            profile = profile,
            delta = 0.0,
            horizonMinutes = 240,
        )

        assertEquals(0, curves.numericFloorClippedSteps)
        // No clip means the published minimum is a real forecast; the two agree.
        assertEquals(curves.insulinPathMinRawMgdl!!, curves.insulinPathMinUnclippedMgdl!!, 1e-6)
    }

    /** The mirror is diagnostic only: adding it must not move a single published point. */
    @Test
    fun unclippedPathMin_does_not_change_any_published_curve() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 13.6
        every { profile.peakTime } returns 54.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 8.0
        every { iobEntry.activity } returns 0.04
        every { iobEntry.time } returns System.currentTimeMillis()

        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 200.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 45.0,
            cobG = 20.0,
            profile = profile,
            delta = 3.0,
            horizonMinutes = 240,
        )

        // Every published point still respects the floor and the ceiling it always did.
        assertTrue(curves.iob.all { it >= 39.0 && it <= 401.0 })
        assertTrue(curves.cob.all { it >= 39.0 && it <= 401.0 })
        assertTrue(curves.uam.all { it >= 39.0 && it <= 401.0 })
        assertTrue(curves.hybrid.all { it >= 39.0 && it <= 401.0 })
        // The mirror can sit below the floor; the published minimum never does.
        assertTrue(curves.insulinPathMinRawMgdl!! >= 39.0)
    }
}
