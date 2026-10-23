package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Soft floor telemetry of the insulin path-min, and the two EGP guards of `AdvancedPredictionEngine`.
 *
 * Port of `PkpdSoftFloorPathMinTest` from `dev_OAPSAIMI`. What changed:
 * - the relaxed mockk profile is [zeroedAimiProfile], and the mocked [IobTotal] rows are real data
 *   objects with the same three fields set (`time`, `activity`, `iob`).
 * - `org.json` `getBoolean` reads become kotlinx `jsonPrimitive.boolean`.
 * - `System.currentTimeMillis()` is [aimiWallClockMs]; Truth is replaced by `kotlin.test`.
 * The production code is the same as on dev apart from the JSON type and the clock.
 */
class PkpdSoftFloorPathMinTest {

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun assertNear(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected +- $tolerance but was $actual")
    }

    @Test
    fun fromCurvesReportsAppliedWhenEndoLiftedInsulin() {
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
        assertTrue(telemetry.applied)
        assertEquals(39.0, telemetry.rawPathMinMgdl)
        assertEquals(80.0, telemetry.softPathMinMgdl)
        assertEquals("egp_applied_on_insulin_curves", telemetry.reason)
        assertFalse(telemetry.toJsonObject().flag("shadow_only"))
        assertTrue(telemetry.toJsonObject().flag("applied"))
    }

    @Test
    fun fromCurvesDisabledEndoNotApplied() {
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
        assertFalse(telemetry.applied)
        assertEquals("endo_reversion_disabled", telemetry.reason)
    }

    @Test
    fun liftFloorBandPointsRaisesOnlyFloorBand() {
        val lifted = PkpdSoftFloorPathMin.liftFloorBandPoints(listOf(180, 39, 100, 42), 80.0)
        assertEquals(listOf(180, 80, 100, 80), lifted)
    }

    @Test
    fun fromCurvesReportsSuppressedWhenFallingTrendGuardTriggered() {
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
        assertFalse(telemetry.applied)
        assertTrue(telemetry.suppressedByFallingTrend)
        assertEquals("endo_suppressed_falling_trend", telemetry.reason)
        assertTrue(telemetry.toJsonObject().flag("suppressed_by_falling_trend"))
    }

    // Guard A (2026-07-22): EGP anchor capped at current BG - lifts the floor-39 artefact but never
    // above where the patient really sits on a low plateau (observed BG=70 -> engine used to predict 80).
    @Test
    fun predictCurvesGuardACapsReversionAtCurrentBgOnLowPlateau() {
        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 70.0,
            iobArray = decayingIob(activeUntilMin = 30, activity = 0.06),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = zeroedAimiProfile(carbRatio = 10.0, peakTime = 75.0),
            delta = 0.0,
            horizonMinutes = 120,
            endogenousReversionEnabled = true,
        )
        assertTrue(curves.endogenousReversionOnInsulinCurves)
        assertFalse(curves.endogenousReversionSuppressedByTrend)
        // Floor-39 artefact still corrected (lifted off the numeric floor)...
        assertTrue(curves.iob.last() > 39.0, "iob terminal ${curves.iob.last()}")
        // ...but Guard A never predicts a rise above the current BG.
        assertTrue(curves.iob.last() <= 70.0, "iob terminal ${curves.iob.last()}")
    }

    // Guard B (2026-07-22): while BG is falling hard (delta <= -3), EGP is fully suspended so the
    // safety path-min stays pessimistic; a flat tick (delta 0) still lifts toward baseline.
    @Test
    fun predictCurvesGuardBSuspendsReversionWhileFallingHard() {
        val profile = zeroedAimiProfile(carbRatio = 10.0, peakTime = 75.0)
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
        assertTrue(fallingHard.endogenousReversionSuppressedByTrend)
        assertFalse(fallingHard.endogenousReversionOnInsulinCurves)
        assertNear(39.0, fallingHard.iob.last(), 0.01)
        // Contrast: same curves but flat -> EGP lifts off the floor toward baseline (Guard B not active).
        assertFalse(flat.endogenousReversionSuppressedByTrend)
        assertTrue(flat.iob.last() > 39.0, "iob terminal ${flat.iob.last()}")
    }

    // Guard A locked at the telemetry layer: even if a future engine change stopped capping the
    // hybrid terminal (here forced to 80 while current BG = 70), fromCurves must still bound soft <= BG.
    @Test
    fun fromCurvesGuardACapsSoftAtCurrentBgEvenIfHybridTerminalStale() {
        val curves = AdvancedPredictionCurves(
            iob = listOf(70.0, 39.0, 55.0),   // first point = current BG = 70
            cob = listOf(70.0, 39.0, 55.0),
            uam = listOf(70.0, 39.0, 55.0),
            zt = listOf(70.0, 39.0, 55.0),
            hybrid = listOf(70.0, 80.0, 80.0), // stale terminal at 80, not capped
            insulinPathMinRawMgdl = 39.0,
            insulinPathMinSoftMgdl = 55.0,
            endogenousReversionOnInsulinCurves = true,
        )
        val telemetry = PkpdSoftFloorPathMin.fromCurves(curves, endogenousReversionEnabled = true)
        assertTrue(telemetry.applied)
        // Dynamic cap = min(80, max(70, 39)) = 70 -> soft pinned at 70, not the stale 80 terminal.
        assertEquals(70.0, telemetry.softPathMinMgdl)
    }

    // Guard B fail-closed: a delta that is not finite (unknown trend) must suspend EGP, never lift.
    @Test
    fun predictCurvesGuardBSuspendsReversionOnNonFiniteDelta() {
        val curves = AdvancedPredictionEngine.predictCurves(
            currentBG = 120.0,
            iobArray = decayingIob(activeUntilMin = 30, activity = 0.06),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = zeroedAimiProfile(carbRatio = 10.0, peakTime = 75.0),
            delta = Double.NaN,
            horizonMinutes = 120,
            endogenousReversionEnabled = true,
        )
        assertTrue(curves.endogenousReversionSuppressedByTrend)
        assertFalse(curves.endogenousReversionOnInsulinCurves)
        assertNear(39.0, curves.iob.last(), 0.01)
    }

    /** Insulin activity that drives BG down for [activeUntilMin] then decays to 0 (insulin used up). */
    private fun decayingIob(activeUntilMin: Int, activity: Double): Array<IobTotal> {
        val now = aimiWallClockMs()
        return (0..120 step 5).map { m ->
            IobTotal(
                time = now + m * 60_000L,
                iob = 3.0,
                activity = if (m <= activeUntilMin) activity else 0.0,
            )
        }.toTypedArray()
    }
}
