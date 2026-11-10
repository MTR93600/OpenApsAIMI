package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class PkpdSoftFloorPathMinTest {

    @Test
    fun fromCurves_reportsApplied_whenEndoLiftedInsulin() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(180.0, 41.0, 55.0),
            cob = listOf(180.0, 50.0, 60.0),
            uam = listOf(180.0, 55.0, 65.0),
            zt = listOf(180.0, 41.0, 55.0),
            hybrid = listOf(180.0, 70.0, 80.0),
            insulinPathMinRawMgdl = 39.0,
            insulinPathMinSoftMgdl = 41.0,
            endogenousReversionOnInsulinCurves = true,
        )
        val telemetry = PkpdSoftFloorPathMin.fromCurves(curves, endogenousReversionEnabled = true)
        assertThat(telemetry.applied).isTrue()
        assertThat(telemetry.rawPathMinMgdl).isEqualTo(39.0)
        assertThat(telemetry.softPathMinMgdl).isEqualTo(80.0)
        assertThat(telemetry.reason).isEqualTo("egp_applied_on_insulin_curves")
        assertThat(telemetry.toJsonObject().getBoolean("shadow_only")).isFalse()
        assertThat(telemetry.toJsonObject().getBoolean("applied")).isTrue()
    }

    @Test
    fun fromCurves_disabledEndo_notApplied() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(180.0, 39.0),
            cob = listOf(180.0, 39.0),
            uam = listOf(180.0, 39.0),
            zt = listOf(180.0, 39.0),
            hybrid = listOf(180.0, 80.0),
            insulinPathMinRawMgdl = 39.0,
            insulinPathMinSoftMgdl = 39.0,
            endogenousReversionOnInsulinCurves = false,
        )
        val telemetry = PkpdSoftFloorPathMin.fromCurves(curves, endogenousReversionEnabled = false)
        assertThat(telemetry.applied).isFalse()
        assertThat(telemetry.reason).isEqualTo("endo_reversion_disabled")
    }

    @Test
    fun liftFloorBandPoints_raisesOnlyFloorBand() {
        val lifted = PkpdSoftFloorPathMin.liftFloorBandPoints(listOf(180, 39, 100, 42), 80.0)
        assertThat(lifted).containsExactly(180, 80, 100, 80).inOrder()
    }

    @Test
    fun fromCurves_reportsSuppressed_whenFallingTrendGuardTriggered() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(120.0, 39.0, 39.0),
            cob = listOf(120.0, 39.0, 39.0),
            uam = listOf(120.0, 39.0, 39.0),
            zt = listOf(120.0, 39.0, 39.0),
            hybrid = listOf(120.0, 39.0, 39.0),
            insulinPathMinRawMgdl = 39.0,
            insulinPathMinSoftMgdl = 39.0,
            endogenousReversionOnInsulinCurves = false,
            endogenousReversionSuppressedByTrend = true,
        )
        val telemetry = PkpdSoftFloorPathMin.fromCurves(curves, endogenousReversionEnabled = true)
        assertThat(telemetry.applied).isFalse()
        assertThat(telemetry.suppressedByFallingTrend).isTrue()
        assertThat(telemetry.reason).isEqualTo("endo_suppressed_falling_trend")
        assertThat(telemetry.toJsonObject().getBoolean("suppressed_by_falling_trend")).isTrue()
    }

    // Guard A (2026-07-22): EGP anchor capped at current BG — lifts the floor-39 artefact but never
    // above where the patient actually sits on a low plateau (observed BG=70 → engine used to predict 80).
    @Test
    fun predictCurves_guardA_capsReversionAtCurrentBgOnLowPlateau() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0
        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 70.0,
            iobArray = decayingIob(activeUntilMin = 30, activity = 0.06),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            delta = 0.0,
            horizonMinutes = 120,
            endogenousReversionEnabled = true,
        )
        assertThat(curves.endogenousReversionOnInsulinCurves).isTrue()
        assertThat(curves.endogenousReversionSuppressedByTrend).isFalse()
        // Floor-39 artefact still corrected (lifted off the numeric floor)…
        assertThat(curves.iob.last()).isGreaterThan(39.0)
        // …but Guard A never predicts a rise above the current BG.
        assertThat(curves.iob.last()).isAtMost(70.0)
    }

    // Guard B (2026-07-22): while BG is falling hard (delta ≤ -3), EGP is fully suspended so the
    // safety path-min stays pessimistic; a flat tick (delta 0) still lifts toward baseline.
    @Test
    fun predictCurves_guardB_suspendsReversionWhileFallingHard() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0
        val fallingHard = AdvancedPredictionEngine.predictCurves(
            currentBG = 120.0,
            iobArray = decayingIob(activeUntilMin = 30, activity = 0.06),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            delta = -11.0,
            horizonMinutes = 120,
            endogenousReversionEnabled = true,
        )
        val flat = AdvancedPredictionEngine.predictCurves(
            currentBG = 120.0,
            iobArray = decayingIob(activeUntilMin = 30, activity = 0.06),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            delta = 0.0,
            horizonMinutes = 120,
            endogenousReversionEnabled = true,
        )
        assertThat(fallingHard.endogenousReversionSuppressedByTrend).isTrue()
        assertThat(fallingHard.endogenousReversionOnInsulinCurves).isFalse()
        assertThat(fallingHard.iob.last()).isWithin(0.01).of(39.0)
        // Contrast: same curves but flat → EGP lifts off the floor toward baseline (Guard B inactive).
        assertThat(flat.endogenousReversionSuppressedByTrend).isFalse()
        assertThat(flat.iob.last()).isGreaterThan(39.0)
    }

    // Guard A locked at the telemetry layer: even if a future engine change stopped capping the
    // hybrid terminal (here forced to 80 while current BG = 70), fromCurves must still bound soft ≤ BG.
    @Test
    fun fromCurves_guardA_capsSoftAtCurrentBg_evenIfHybridTerminalStale() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(70.0, 39.0, 55.0),   // first point = current BG = 70
            cob = listOf(70.0, 39.0, 55.0),
            uam = listOf(70.0, 39.0, 55.0),
            zt = listOf(70.0, 39.0, 55.0),
            hybrid = listOf(70.0, 80.0, 80.0), // stale/un-capped terminal at 80
            insulinPathMinRawMgdl = 39.0,
            insulinPathMinSoftMgdl = 55.0,
            endogenousReversionOnInsulinCurves = true,
        )
        val telemetry = PkpdSoftFloorPathMin.fromCurves(curves, endogenousReversionEnabled = true)
        assertThat(telemetry.applied).isTrue()
        // Dynamic cap = min(80, max(70, 39)) = 70 → soft pinned at 70, not the stale-80 terminal.
        assertThat(telemetry.softPathMinMgdl).isEqualTo(70.0)
    }

    // Guard B fail-closed: a non-finite delta (unknown trend) must suspend EGP, never lift.
    @Test
    fun predictCurves_guardB_suspendsReversionOnNonFiniteDelta() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0
        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 120.0,
            iobArray = decayingIob(activeUntilMin = 30, activity = 0.06),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            delta = Double.NaN,
            horizonMinutes = 120,
            endogenousReversionEnabled = true,
        )
        assertThat(curves.endogenousReversionSuppressedByTrend).isTrue()
        assertThat(curves.endogenousReversionOnInsulinCurves).isFalse()
        assertThat(curves.iob.last()).isWithin(0.01).of(39.0)
    }

    /** Insulin activity that drives BG down for [activeUntilMin] then decays to 0 (insulin exhausted). */
    private fun decayingIob(activeUntilMin: Int, activity: Double): Array<IobTotal> {
        val now = System.currentTimeMillis()
        return (0..120 step 5).map { m ->
            mockk<IobTotal>().also {
                every { it.time } returns now + m * 60_000L
                every { it.activity } returns if (m <= activeUntilMin) activity else 0.0
                every { it.iob } returns 3.0
            }
        }.toTypedArray()
    }
}
