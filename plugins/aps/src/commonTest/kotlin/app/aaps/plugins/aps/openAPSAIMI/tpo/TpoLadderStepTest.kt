package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TPO only makes dosing more careful. A "step down" must never raise a value, and a "step up" must
 * never lower one. One step moves one rung at most; below the ladder a step down cuts by a fixed share.
 * The meal factor keys are percent (100 = neutral), so their ladder is on that scale too.
 */
class TpoLadderStepTest {

    private fun prefs(vararg values: Pair<DoubleKey, Double>): MapPreferences =
        MapPreferences().apply { values.forEach { (key, value) -> doubles[key] = value } }

    private fun stepDownMaxSmb(value: Double, steps: Int = 1): Double? =
        TpoLadderSupport.stepDownLadder(
            prefs(DoubleKey.OApsAIMIMaxSMB to value),
            DoubleKey.OApsAIMIMaxSMB,
            TpoLadderSupport.MAX_SMB_LADDER,
            steps,
        )

    @Test
    fun stepDownBelowTheLadderFloorNeverRaisesTheValue() {
        // The old code moved 0.3 to the nearest rung (0.8) and so raised Max SMB.
        val target = assertNotNull(stepDownMaxSmb(0.30))
        assertTrue(target < 0.30, "step down raised 0.30 to $target")
        assertEquals(0.30 * TpoLadderSupport.OUTSIDE_LADDER_STEP_DOWN_FACTOR, target, 1e-9)
    }

    @Test
    fun stepDownBetweenTwoRungsMovesToTheNextRungBelowOnly() {
        // 2.0 lies between 1.80 and 2.40. The old code snapped to 1.80 first and then went to 1.30.
        assertEquals(1.80, assertNotNull(stepDownMaxSmb(2.0)), 1e-9)
    }

    @Test
    fun stepDownFromARungMovesOneRung() {
        assertEquals(1.00, assertNotNull(stepDownMaxSmb(1.30)), 1e-9)
    }

    @Test
    fun twoStepsMoveTwoRungs() {
        assertEquals(1.00, assertNotNull(stepDownMaxSmb(1.80, steps = 2)), 1e-9)
    }

    @Test
    fun stepDownAboveTheLadderMovesToTheTopRung() {
        // The top rung is the next rung below the value. A 15 % cut (3.0 -> 2.55) was too weak.
        assertEquals(2.40, assertNotNull(stepDownMaxSmb(3.0)), 1e-9)
        assertEquals(2.40, assertNotNull(stepDownMaxSmb(5.0)), 1e-9)
        assertEquals(2.40, assertNotNull(stepDownMaxSmb(2.60)), 1e-9)
    }

    @Test
    fun twoStepsAboveTheLadderGoToTheTopRungAndOneRungBelow() {
        assertEquals(1.80, assertNotNull(stepDownMaxSmb(3.0, steps = 2)), 1e-9)
    }

    @Test
    fun stepDownOnTheLowestRungProposesNothing() {
        assertNull(stepDownMaxSmb(0.80))
    }

    @Test
    fun stepDownNeverGoesBelowTheKeyMinimum() {
        val target = TpoLadderSupport.stepDownLadder(
            prefs(DoubleKey.OApsAIMILunchFactor to 1.0),
            DoubleKey.OApsAIMILunchFactor,
            TpoLadderSupport.MEAL_FACTOR_LADDER,
            1,
        )
        assertNull(target)
    }

    @Test
    fun stepUpAboveTheLadderNeverLowersTheValue() {
        // The old code moved a hypo floor of 90 to the top rung 88 and so lowered it.
        val target = TpoLadderSupport.stepUpLadder(
            prefs(DoubleKey.AimiTubeHypoFloorMgdl to 90.0),
            DoubleKey.AimiTubeHypoFloorMgdl,
            TpoLadderSupport.TUBE_HYPO_FLOOR_LADDER,
            1,
        )
        assertNull(target)
    }

    @Test
    fun stepUpBetweenTwoRungsMovesToTheNextRungAboveOnly() {
        val target = TpoLadderSupport.stepUpLadder(
            prefs(DoubleKey.AimiTubeHypoFloorMgdl to 79.0),
            DoubleKey.AimiTubeHypoFloorMgdl,
            TpoLadderSupport.TUBE_HYPO_FLOOR_LADDER,
            1,
        )
        assertEquals(80.0, assertNotNull(target), 1e-9)
    }

    private fun stepUpHypoFloor(value: Double, steps: Int): Double? =
        TpoLadderSupport.stepUpLadder(
            prefs(DoubleKey.AimiTubeHypoFloorMgdl to value),
            DoubleKey.AimiTubeHypoFloorMgdl,
            TpoLadderSupport.TUBE_HYPO_FLOOR_LADDER,
            steps,
        )

    @Test
    fun stepUpBelowTheLadderFirstGoesToTheLowestRungWithoutUsingAStep() {
        // Same size as before step T1: 70 goes to the lowest rung (72), then 2 rungs up to 80.
        assertEquals(80.0, assertNotNull(stepUpHypoFloor(70.0, steps = 2)), 1e-9)
        assertEquals(76.0, assertNotNull(stepUpHypoFloor(70.0, steps = 1)), 1e-9)
    }

    @Test
    fun mealFactorLadderIsOnThePercentScale() {
        assertEquals(listOf(70.0, 85.0, 100.0, 115.0, 130.0), TpoLadderSupport.MEAL_FACTOR_LADDER)
    }

    private fun exhaustedPlan(preferences: MapPreferences) = TpoDeltaBuilder.buildPlan(
        proposal = TpoProposal(
            packId = TpoPackId.EXHAUSTED_RECOVERY,
            tier = TuningStepTier.MICRO,
            algoConfidence = 0.8,
            reasonCodes = listOf("TEST"),
        ),
        preferences = preferences,
        hypoLoad = 0.2,
        t3cBrittle = false,
    )

    @Test
    fun recoveryLowersTheDefaultMealFactorsByOneShareInsteadOfToOnePercent() {
        // The defaults (50 %) are below the ladder. The old 1.0 scale ladder set them to 1 %.
        val plan = exhaustedPlan(MapPreferences())
        val lunch = plan.changes.first { it.key == DoubleKey.OApsAIMILunchFactor }
        val dinner = plan.changes.first { it.key == DoubleKey.OApsAIMIDinnerFactor }
        assertEquals(50.0, lunch.oldValue)
        assertEquals(50.0 * TpoLadderSupport.OUTSIDE_LADDER_STEP_DOWN_FACTOR, lunch.newValue as Double, 1e-9)
        assertEquals(50.0 * TpoLadderSupport.OUTSIDE_LADDER_STEP_DOWN_FACTOR, dinner.newValue as Double, 1e-9)
    }

    @Test
    fun recoveryMovesANeutralMealFactorOneRungDown() {
        val plan = exhaustedPlan(
            prefs(DoubleKey.OApsAIMILunchFactor to 100.0, DoubleKey.OApsAIMIDinnerFactor to 115.0),
        )
        assertEquals(85.0, plan.changes.first { it.key == DoubleKey.OApsAIMILunchFactor }.newValue as Double, 1e-9)
        assertEquals(100.0, plan.changes.first { it.key == DoubleKey.OApsAIMIDinnerFactor }.newValue as Double, 1e-9)
    }

    @Test
    fun recoveryNeverRaisesALowMaxSmb() {
        val plan = exhaustedPlan(
            prefs(DoubleKey.OApsAIMIMaxSMB to 0.30, DoubleKey.OApsAIMIHighBGMaxSMB to 0.50),
        )
        val maxSmb = plan.changes.single { it.key == DoubleKey.OApsAIMIMaxSMB }
        val highBgMaxSmb = plan.changes.single { it.key == DoubleKey.OApsAIMIHighBGMaxSMB }
        assertEquals(0.30, maxSmb.oldValue)
        assertEquals(0.255, maxSmb.newValue as Double, 1e-9)
        assertEquals(0.50, highBgMaxSmb.oldValue)
        assertEquals(0.425, highBgMaxSmb.newValue as Double, 1e-9)
    }

    private fun tailDamping(effective: Double, steps: Int): Double? =
        TpoLadderSupport.strengthenTailDamping(prefs(DoubleKey.OApsAIMISmbTailDamping to effective), steps)

    @Test
    fun strengthenTailDampingMovesTowardStrongerDamping() {
        assertEquals(0.70, assertNotNull(tailDamping(0.85, steps = 1)), 1e-9)
    }

    @Test
    fun strengthenTailDampingNeverWeakensAValueBelowTheBand() {
        // 0.60 is a valid stored damping (above the legacy cut-off 0.55) and stronger than 0.70.
        assertNull(tailDamping(0.60, steps = 1))
        assertNull(tailDamping(0.60, steps = 2))
    }

    private fun poorSleepStrongPlan(preferences: MapPreferences) = TpoDeltaBuilder.buildPlan(
        proposal = TpoProposal(
            packId = TpoPackId.POOR_SLEEP_WINDOW,
            tier = TuningStepTier.STRONG,
            algoConfidence = 0.8,
            reasonCodes = listOf("TEST"),
        ),
        preferences = preferences,
        hypoLoad = 0.2,
        t3cBrittle = false,
    )

    @Test
    fun poorSleepPackNeverRaisesTheExerciseOrLateFatSmbMultiplier() {
        // These keys multiply the SMB: a higher value gives more insulin. The old code moved the
        // defaults 0.60 -> 0.72 and 0.70 -> 0.80 in a pack meant to protect.
        val plan = poorSleepStrongPlan(
            prefs(DoubleKey.OApsAIMISmbExerciseDamping to 0.60, DoubleKey.OApsAIMISmbLateFatDamping to 0.70),
        )
        assertTrue(plan.changes.none { it.key == DoubleKey.OApsAIMISmbExerciseDamping })
        assertTrue(plan.changes.none { it.key == DoubleKey.OApsAIMISmbLateFatDamping })
    }

    @Test
    fun poorSleepPackLowersAnExerciseOrLateFatMultiplierAboveTheBand() {
        val plan = poorSleepStrongPlan(
            prefs(DoubleKey.OApsAIMISmbExerciseDamping to 0.95, DoubleKey.OApsAIMISmbLateFatDamping to 0.98),
        )
        assertEquals(0.85, plan.changes.single { it.key == DoubleKey.OApsAIMISmbExerciseDamping }.newValue as Double, 1e-9)
        assertEquals(0.90, plan.changes.single { it.key == DoubleKey.OApsAIMISmbLateFatDamping }.newValue as Double, 1e-9)
    }
}
