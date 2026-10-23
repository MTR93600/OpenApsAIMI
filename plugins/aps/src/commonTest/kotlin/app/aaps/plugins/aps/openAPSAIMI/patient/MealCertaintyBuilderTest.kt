package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port of `MealCertaintyBuilderTest` from `dev_OAPSAIMI` (`plugins/aps/src/test`).
 *
 * [MealCertaintyBuilder] is in commonMain and pure, so the test runs on every target. Adapted: Truth
 * and JUnit replaced by `kotlin.test`, names in camelCase. The cases and the numbers are the same as
 * on dev; the builder logic is the same as on dev (only the JSON export differs).
 */
class MealCertaintyBuilderTest {

    @Test
    fun digestionRisingAboveBandIsHigh() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
            ),
        )
        assertEquals(MealCertaintyLevel.HIGH, mc.level)
        assertTrue(mc.supportsMealOverProtective)
        assertEquals(MealRiseGeometry.OK, mc.riseGeometry)
    }

    @Test
    fun fallingDeltaIsNotHigh() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 280.0,
                deltaMgdl5m = -6.0,
                targetBgMgdl = 100.0,
            ),
        )
        assertNotEquals(MealCertaintyLevel.HIGH, mc.level)
        assertEquals(MealRiseGeometry.FALLING, mc.riseGeometry)
    }

    @Test
    fun effortVetoBlocksHighAndMedWhenUndeclared() {
        // Mild hyper + modest rise: effort veto must still pin LOW (real activity risk).
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
                cobG = 0.0,
                effortVeto = true,
            ),
        )
        assertEquals(MealCertaintyLevel.LOW, mc.level)
        assertFalse(mc.supportsMealSupport)
    }

    @Test
    fun effortVetoOverriddenByStrongUndeclaredHyperRise() {
        // 25/07 14:42 style: BG≈248 Δ≈23 FIRST_WAVE + effort_veto from postprandial HR noise.
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 1.0,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 248.0,
                deltaMgdl5m = 22.7,
                targetBgMgdl = 100.0,
                cobG = 0.0,
                effortVeto = true,
            ),
        )
        assertEquals(MealCertaintyLevel.HIGH, mc.level)
        assertTrue(mc.supportsMealOverProtective)
        assertTrue(mc.supportsMealSupport)
        assertTrue((mc.reasons).contains("level_high_digestion_overrides_effort_veto"))
    }

    @Test
    fun hypoTerminalConflictForcesNone() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
                scenarioPathMinMgdl = 60.0,
            ),
        )
        assertEquals(MealCertaintyLevel.NONE, mc.level)
        assertEquals(MealTerminalsAgree.HYPO_CONFLICT, mc.terminalsAgree)
    }

    @Test
    fun mealProbableWithWeakRiseIsMed() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.MEAL_PROBABLE,
                mealBranchConfidence = 0.6,
                bgMgdl = 150.0,
                deltaMgdl5m = 0.5,
                targetBgMgdl = 100.0,
            ),
        )
        assertEquals(MealCertaintyLevel.MED, mc.level)
        assertTrue(mc.supportsMealSupport)
        assertFalse(mc.supportsMealOverProtective)
    }

    @Test
    fun pkpdFloorConflictStillAllowsHighWhenPathSafe() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.8,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 190.0,
                deltaMgdl5m = 3.0,
                targetBgMgdl = 100.0,
                pkpdEventualMgdl = 39.0,
                scenarioTerminalMgdl = 180.0,
                scenarioPathMinMgdl = 95.0,
            ),
        )
        assertEquals(MealTerminalsAgree.PKPD_FLOOR_CONFLICT, mc.terminalsAgree)
        assertEquals(MealCertaintyLevel.HIGH, mc.level)
    }

    @Test
    fun softCorroborationFromPhysioRequiresIdleElevatedHr() {
        assertTrue(MealCertaintyBuilder.softCorroborationFromPhysio(
                PhysioLiveDigest(hrNowBpm = 80, rhrRestingBpm = 55, activityState = "IDLE", stepsLast15m = 10),
            ),)
        assertFalse(MealCertaintyBuilder.softCorroborationFromPhysio(
                PhysioLiveDigest(hrNowBpm = 100, rhrRestingBpm = 55, activityState = "WALKING", stepsLast15m = 400),
            ),)
    }

    // --- effort SMB floor on a certain meal (2026-08-10 lunch) ---

    private fun certaintyAt(level: MealCertaintyLevel): MealCertainty =
        MealCertainty.NONE.copy(level = level)

    @Test
    fun effortSmbFactorForFloorsTheReductionOnlyWhenTheMealIsCertain() {
        // The 12:36 tick of 2026-08-10: effort asked for 0.56 at BG 237 with the meal certain.
        assertEquals(0.75, MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.56), 1e-9)
        // The dinner of 2026-08-10 never left LOW — it must not move.
        assertEquals(0.56, MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.LOW), 0.56), 1e-9)
        assertEquals(0.56, MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.MED), 0.56), 1e-9)
        assertEquals(0.56, MealCertaintyBuilder.effortSmbFactorFor(null, 0.56), 1e-9)
    }

    @Test
    fun effortSmbFactorForNeverRaisesAboveWhatEffortAskedWhenItAskedForMore() {
        assertEquals(0.90, MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.90), 1e-9)
    }

    @Test
    fun effortSmbFactorForIsAlwaysAReduction() {
        for (level in MealCertaintyLevel.entries) {
            assertTrue((MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(level), 1.0)) <= 1.0)
            assertTrue((MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(level), 0.0)) <= 1.0)
        }
        assertEquals(1.0, MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 1.4), 1e-9)
        assertEquals(1.0, MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), Double.NaN), 1e-9)
    }

    @Test
    fun effortSmbFactorForATotalEffortStopStillKeepsAQuarterOnACertainMeal() {
        assertEquals(0.75, MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.0), 1e-9)
    }

    // --- the anticipated-rise override of a stale effort veto (2026-08-14 lunch) ---

    /**
     * The tick the whole change is aimed at.
     *
     * 2026-08-14 13:16, BG 158.4 rising +27.1 mg/dL per 5 min, no carbs on board, trunk
     * `DIGESTION_ACTIVE`, effort veto live from a walk two hours earlier. The level was `LOW`, so the
     * tree intent stayed `PROTECTIVE`, the Harmonia lift was unreachable and the effort multiplier ran at
     * x0.45. `HIGH` first arrived at BG 204, thirty minutes later.
     */
    private fun anticipatedRiseInput(
        bgMgdl: Double,
        deltaMgdl5m: Double,
        shortAvgDeltaMgdl5m: Double,
        effortLive: Boolean,
        absorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
    ) = MealCertaintyBuilder.Input(
        trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
        mealBranchConfidence = 0.95,
        digestionDetected = true,
        absorptionPhase = absorptionPhase,
        bgMgdl = bgMgdl,
        deltaMgdl5m = deltaMgdl5m,
        targetBgMgdl = 100.0,
        cobG = 0.0,
        effortVeto = true,
        shortAvgDeltaMgdl5m = shortAvgDeltaMgdl5m,
        effortLive = effortLive,
    )

    @Test
    fun anticipatedRiseReachesHighBelowBg200WhenTheEffortIsOnlyAMemory() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 158.4, deltaMgdl5m = 27.1, shortAvgDeltaMgdl5m = 14.0, effortLive = false),
        )
        assertEquals(MealCertaintyLevel.HIGH, mc.level)
        assertTrue(mc.supportsMealOverProtective)
        assertTrue((mc.reasons).contains("level_high_digestion_anticipated_rise"))
    }

    /** The term that keeps the protection: live movement is never overridden, at any rise. */
    @Test
    fun anticipatedRiseLiveMovementKeepsTheEffortVeto() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 158.4, deltaMgdl5m = 27.1, shortAvgDeltaMgdl5m = 14.0, effortLive = true),
        )
        assertNotEquals(MealCertaintyLevel.HIGH, mc.level)
        assertFalse(mc.supportsMealOverProtective)
    }

    /**
     * A genuine dawn or stress ramp must not open the meal channel. Measured over 952 pooled ticks,
     * genuine endogenous ramps peaked at 9.1 mg/dL per 5 min; the threshold is 10.
     */
    @Test
    fun anticipatedRiseAGenuineEndogenousRampStaysBelowTheThreshold() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 165.0, deltaMgdl5m = 9.1, shortAvgDeltaMgdl5m = 7.0, effortLive = false),
        )
        assertNotEquals(MealCertaintyLevel.HIGH, mc.level)
    }

    /** A single noisy sample cannot open it — the short average has to agree. */
    @Test
    fun anticipatedRiseNeedsBothWindowsToAgree() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 165.0, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 2.0, effortLive = false),
        )
        assertNotEquals(MealCertaintyLevel.HIGH, mc.level)
    }

    /** The absorption wave must be active, exactly as the BG-200 clause already required. */
    @Test
    fun anticipatedRiseNeedsAnActiveAbsorptionWave() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(
                bgMgdl = 165.0, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 14.0, effortLive = false,
                absorptionPhase = MealAbsorptionPhase.NONE,
            ),
        )
        assertNotEquals(MealCertaintyLevel.HIGH, mc.level)
    }

    /**
     * The hypoglycaemia cost, asserted rather than argued.
     *
     * `aboveMealBand` requires BG > target + 30, so below about 130 mg/dL the override cannot fire
     * however steep the rise. The corpus contains a day with BG 45, 57 and 63; on all 39 ticks below
     * BG 75 the level was NONE and glucose was falling.
     */
    @Test
    fun anticipatedRiseCannotFireBelowTheMealBand() {
        for (bg in listOf(45.0, 57.0, 63.0, 95.0, 125.0)) {
            val mc = MealCertaintyBuilder.evaluate(
                anticipatedRiseInput(bgMgdl = bg, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 14.0, effortLive = false),
            )
            assertNotEquals(MealCertaintyLevel.HIGH, mc.level)
        }
    }

    /** A hypoglycaemia conflict in the terminals still wins over everything. */
    @Test
    fun anticipatedRiseYieldsToAHypoTerminalConflict() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 165.0, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 14.0, effortLive = false)
                .copy(scenarioPathMinMgdl = 62.0),
        )
        assertEquals(MealTerminalsAgree.HYPO_CONFLICT, mc.terminalsAgree)
        assertEquals(MealCertaintyLevel.NONE, mc.level)
    }

    /** The default input keeps the previous behaviour: an unsupplied caller cannot open the override. */
    @Test
    fun anticipatedRiseDefaultsKeepTheOverrideShut() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
                mealBranchConfidence = 0.95,
                digestionDetected = true,
                absorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                bgMgdl = 158.4,
                deltaMgdl5m = 27.1,
                targetBgMgdl = 100.0,
                effortVeto = true,
            ),
        )
        assertNotEquals(MealCertaintyLevel.HIGH, mc.level)
    }

    /** The existing BG-200 clause is untouched and still reaches HIGH on its own terms. */
    @Test
    fun anticipatedRiseTheDeepHyperClauseStillWorks() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 237.0, deltaMgdl5m = 4.5, shortAvgDeltaMgdl5m = 0.0, effortLive = true),
        )
        assertEquals(MealCertaintyLevel.HIGH, mc.level)
        assertTrue((mc.reasons).contains("level_high_digestion_overrides_effort_veto"))
    }
}
