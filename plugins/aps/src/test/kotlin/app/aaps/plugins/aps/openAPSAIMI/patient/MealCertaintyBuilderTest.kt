package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealCertaintyBuilderTest {

    @Test
    fun digestionRisingAboveBand_isHigh() {
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
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.supportsMealOverProtective).isTrue()
        assertThat(mc.riseGeometry).isEqualTo(MealRiseGeometry.OK)
    }

    @Test
    fun fallingDelta_isNotHigh() {
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
        assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.riseGeometry).isEqualTo(MealRiseGeometry.FALLING)
    }

    @Test
    fun effortVeto_blocksHighAndMedWhenUndeclared() {
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
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.LOW)
        assertThat(mc.supportsMealSupport).isFalse()
    }

    @Test
    fun effortVeto_overriddenByStrongUndeclaredHyperRise() {
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
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.supportsMealOverProtective).isTrue()
        assertThat(mc.supportsMealSupport).isTrue()
        assertThat(mc.reasons).contains("level_high_digestion_overrides_effort_veto")
    }

    @Test
    fun hypoTerminalConflict_forcesNone() {
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
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.NONE)
        assertThat(mc.terminalsAgree).isEqualTo(MealTerminalsAgree.HYPO_CONFLICT)
    }

    @Test
    fun mealProbableWithWeakRise_isMed() {
        val mc = MealCertaintyBuilder.evaluate(
            MealCertaintyBuilder.Input(
                trunkState = GlobalPhysiologicalState.MEAL_PROBABLE,
                mealBranchConfidence = 0.6,
                bgMgdl = 150.0,
                deltaMgdl5m = 0.5,
                targetBgMgdl = 100.0,
            ),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.MED)
        assertThat(mc.supportsMealSupport).isTrue()
        assertThat(mc.supportsMealOverProtective).isFalse()
    }

    @Test
    fun pkpdFloorConflict_stillAllowsHighWhenPathSafe() {
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
        assertThat(mc.terminalsAgree).isEqualTo(MealTerminalsAgree.PKPD_FLOOR_CONFLICT)
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
    }

    @Test
    fun softCorroborationFromPhysio_requiresIdleElevatedHr() {
        assertThat(
            MealCertaintyBuilder.softCorroborationFromPhysio(
                PhysioLiveDigest(hrNowBpm = 80, rhrRestingBpm = 55, activityState = "IDLE", stepsLast15m = 10),
            ),
        ).isTrue()
        assertThat(
            MealCertaintyBuilder.softCorroborationFromPhysio(
                PhysioLiveDigest(hrNowBpm = 100, rhrRestingBpm = 55, activityState = "WALKING", stepsLast15m = 400),
            ),
        ).isFalse()
    }

    // --- effort SMB floor on a certain meal (2026-08-10 lunch) ---

    private fun certaintyAt(level: MealCertaintyLevel): MealCertainty =
        MealCertainty.NONE.copy(level = level)

    @Test
    fun effortSmbFactorFor_floorsTheReductionOnlyWhenTheMealIsCertain() {
        // The 12:36 tick of 2026-08-10: effort asked for 0.56 at BG 237 with the meal certain.
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.56))
            .isWithin(1e-9).of(0.75)
        // The dinner of 2026-08-10 never left LOW — it must not move.
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.LOW), 0.56))
            .isWithin(1e-9).of(0.56)
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.MED), 0.56))
            .isWithin(1e-9).of(0.56)
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(null, 0.56)).isWithin(1e-9).of(0.56)
    }

    @Test
    fun effortSmbFactorFor_neverRaisesAboveWhatEffortAsked_whenItAskedForMore() {
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.90))
            .isWithin(1e-9).of(0.90)
    }

    @Test
    fun effortSmbFactorFor_isAlwaysAReduction() {
        for (level in MealCertaintyLevel.entries) {
            assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(level), 1.0)).isAtMost(1.0)
            assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(level), 0.0)).isAtMost(1.0)
        }
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 1.4))
            .isWithin(1e-9).of(1.0)
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), Double.NaN))
            .isWithin(1e-9).of(1.0)
    }

    @Test
    fun effortSmbFactorFor_aTotalEffortStopStillKeepsAQuarterOnACertainMeal() {
        assertThat(MealCertaintyBuilder.effortSmbFactorFor(certaintyAt(MealCertaintyLevel.HIGH), 0.0))
            .isWithin(1e-9).of(0.75)
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
    fun anticipatedRise_reachesHighBelowBg200_whenTheEffortIsOnlyAMemory() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 158.4, deltaMgdl5m = 27.1, shortAvgDeltaMgdl5m = 14.0, effortLive = false),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.supportsMealOverProtective).isTrue()
        assertThat(mc.reasons).contains("level_high_digestion_anticipated_rise")
    }

    /** The term that keeps the protection: live movement is never overridden, at any rise. */
    @Test
    fun anticipatedRise_liveMovementKeepsTheEffortVeto() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 158.4, deltaMgdl5m = 27.1, shortAvgDeltaMgdl5m = 14.0, effortLive = true),
        )
        assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.supportsMealOverProtective).isFalse()
    }

    /**
     * A genuine dawn or stress ramp must not open the meal channel. Measured over 952 pooled ticks,
     * genuine endogenous ramps peaked at 9.1 mg/dL per 5 min; the threshold is 10.
     */
    @Test
    fun anticipatedRise_aGenuineEndogenousRampStaysBelowTheThreshold() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 165.0, deltaMgdl5m = 9.1, shortAvgDeltaMgdl5m = 7.0, effortLive = false),
        )
        assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
    }

    /** A single noisy sample cannot open it — the short average has to agree. */
    @Test
    fun anticipatedRise_needsBothWindowsToAgree() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 165.0, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 2.0, effortLive = false),
        )
        assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
    }

    /** The absorption wave must be active, exactly as the BG-200 clause already required. */
    @Test
    fun anticipatedRise_needsAnActiveAbsorptionWave() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(
                bgMgdl = 165.0, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 14.0, effortLive = false,
                absorptionPhase = MealAbsorptionPhase.NONE,
            ),
        )
        assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
    }

    /**
     * The hypoglycaemia cost, asserted rather than argued.
     *
     * `aboveMealBand` requires BG > target + 30, so below about 130 mg/dL the override cannot fire
     * however steep the rise. The corpus contains a day with BG 45, 57 and 63; on all 39 ticks below
     * BG 75 the level was NONE and glucose was falling.
     */
    @Test
    fun anticipatedRise_cannotFireBelowTheMealBand() {
        for (bg in listOf(45.0, 57.0, 63.0, 95.0, 125.0)) {
            val mc = MealCertaintyBuilder.evaluate(
                anticipatedRiseInput(bgMgdl = bg, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 14.0, effortLive = false),
            )
            assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
        }
    }

    /** A hypoglycaemia conflict in the terminals still wins over everything. */
    @Test
    fun anticipatedRise_yieldsToAHypoTerminalConflict() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 165.0, deltaMgdl5m = 27.0, shortAvgDeltaMgdl5m = 14.0, effortLive = false)
                .copy(scenarioPathMinMgdl = 62.0),
        )
        assertThat(mc.terminalsAgree).isEqualTo(MealTerminalsAgree.HYPO_CONFLICT)
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.NONE)
    }

    /** The default input keeps the previous behaviour: an unsupplied caller cannot open the override. */
    @Test
    fun anticipatedRise_defaultsKeepTheOverrideShut() {
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
        assertThat(mc.level).isNotEqualTo(MealCertaintyLevel.HIGH)
    }

    /** The existing BG-200 clause is untouched and still reaches HIGH on its own terms. */
    @Test
    fun anticipatedRise_theDeepHyperClauseStillWorks() {
        val mc = MealCertaintyBuilder.evaluate(
            anticipatedRiseInput(bgMgdl = 237.0, deltaMgdl5m = 4.5, shortAvgDeltaMgdl5m = 0.0, effortLive = true),
        )
        assertThat(mc.level).isEqualTo(MealCertaintyLevel.HIGH)
        assertThat(mc.reasons).contains("level_high_digestion_overrides_effort_veto")
    }
}
