package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Describes the "projected error" rework (lot 1) of [DynamicBasalController].
 *
 * The defect it fixed: the old (P, D) pair weighed `0.05` per mg/dL of error against
 * `12 x 0.15 = 1.8` per mg/dL/5min of slope, so **36:1**. A rise of +2.5 mg/dL/5min added +4.5 to the
 * multiplier, which only a glucose 90 mg/dL under target could balance.
 *
 * Port of `DynamicBasalControllerProjectedErrorTest` from `dev_OAPSAIMI` (plugins/aps/src/test).
 * `DynamicBasalController.compute` matches dev; only the reason text is built with `aimiFmt*`
 * instead of `String.format`, and the prefixes the test checks are the same. Adapted: JUnit asserts
 * replaced by `kotlin.test`, comments translated to English, moved to `commonTest`.
 */
class DynamicBasalControllerProjectedErrorTest {

    private val horizon = DynamicBasalController.PROJECTION_HORIZON_MIN

    private fun input(
        bg: Double,
        target: Double,
        delta: Double,
        shortAvgDelta: Double = delta,
        profileBasal: Double = 0.6,
        horizonMin: Double? = null,
    ) = DynamicBasalController.Input(
        bg = bg,
        targetBg = target,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = shortAvgDelta,
        iob = 0.0,
        maxIob = 20.0,
        profileBasal = profileBasal,
        variableSensitivity = 50.0,
        duraISFminutes = 0.0,
        predictedBgOverride = null,
        mode = DynamicBasalController.Mode.STANDARD,
        projectionHorizonMin = horizonMin,
    )

    private fun assertNear(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun legacyGainRatioIs36To1() {
        // Locks the finding behind lot 1, on the old formula.
        val pGainPerMgdl = 0.05
        val dGainPerMgdlPer5min = 12.0 * 0.15
        assertNear(36.0, dGainPerMgdlPer5min / pGainPerMgdl, 1e-9)
    }

    @Test
    fun prod20260802at0836LegacyAsksFiveTimesProfileWhileBelowTarget() {
        // Real tick: BG 104, target 115 (11 mg/dL UNDER target), slope +2.54 -> Mult 5.03x seen.
        val legacy = DynamicBasalController.compute(input(bg = 104.0, target = 115.0, delta = 2.54))
        assertNear(5.02, legacy.rate / 0.6, 0.02)
        assertTrue(legacy.reason.startsWith("PI-Fallback"), legacy.reason)
    }

    @Test
    fun prod20260802at0836ProjectedIsFarMoreConservative() {
        val projected = DynamicBasalController.compute(
            input(bg = 104.0, target = 115.0, delta = 2.54, horizonMin = horizon),
        )
        // Projection: 104 + 2.54 x (60/5) = 134.5 -> error +19.5 -> mult = 1 + 19.5 x 0.05 ~ 1.97
        assertNear(1.97, projected.rate / 0.6, 0.02)
        assertTrue(projected.reason.startsWith("PI-Projected"), projected.reason)
        assertTrue(projected.reason.contains("H=60min"), projected.reason)
    }

    @Test
    fun prod20260802at0841LegacyAsksAlmostEightTimesProfile() {
        val legacy = DynamicBasalController.compute(input(bg = 109.2, target = 115.0, delta = 4.0))
        assertNear(7.91, legacy.rate / 0.6, 0.02)
        val projected = DynamicBasalController.compute(
            input(bg = 109.2, target = 115.0, delta = 4.0, horizonMin = horizon),
        )
        // 109.2 + 4.0 x 12 = 157.2 -> error +42.2 -> mult ~ 3.11
        assertNear(3.11, projected.rate / 0.6, 0.02)
    }

    @Test
    fun steadyGlucoseIsUnchangedByTheRefactor() {
        // With no slope both formulas agree: the rework only changes the weight of the trend.
        val legacy = DynamicBasalController.compute(input(bg = 200.0, target = 100.0, delta = 0.0))
        val projected = DynamicBasalController.compute(
            input(bg = 200.0, target = 100.0, delta = 0.0, horizonMin = horizon),
        )
        assertNear(legacy.rate, projected.rate, 1e-9)
    }

    @Test
    fun genuineHyperStillCorrects() {
        // Lot 1 must not make the engine idle on a real rising hyper.
        val projected = DynamicBasalController.compute(
            input(bg = 250.0, target = 100.0, delta = 3.0, horizonMin = horizon),
        )
        assertTrue(projected.rate / 0.6 > 5.0, "a rising hyper must still be corrected, got ${projected.rate / 0.6}")
    }

    /**
     * Safety invariant of lot 1, checked on rises AND falls.
     *
     * On a fall the projection alone would brake LESS than the old formula (derivative gain 0.6
     * against 1.8), so the controller keeps the minimum of the two.
     */
    @Test
    fun projectedNeverExceedsLegacyOverTheWholeDomainIncludingFalls() {
        var bg = 80.0
        while (bg <= 260.0) {
            var d = -6.0
            while (d <= 6.0) {
                val legacy = DynamicBasalController.compute(input(bg = bg, target = 110.0, delta = d))
                val projected = DynamicBasalController.compute(
                    input(bg = bg, target = 110.0, delta = d, horizonMin = horizon),
                )
                assertTrue(
                    projected.rate <= legacy.rate + 1e-9,
                    "bg=$bg delta=$d: projected ${projected.rate} > legacy ${legacy.rate}",
                )
                d += 0.5
            }
            bg += 20.0
        }
    }

    @Test
    fun fallingBelowTargetStillBrakesToZero() {
        val projected = DynamicBasalController.compute(
            input(bg = 95.0, target = 110.0, delta = -2.0, horizonMin = horizon),
        )
        assertEquals(0.0, projected.rate)
        assertTrue(projected.reason.contains("Brake"), projected.reason)
    }
}
