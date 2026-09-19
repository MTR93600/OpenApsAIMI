package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FCL mode: an "fcl" note plus a low temp target force the meal basal ceiling, with no prebolus.
 *
 * The note alone must not be enough and the temp target alone must not be enough. Both are manual
 * acts, and the temp target is what ends the mode, so the pair is the whole gate.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `41e59f9bc0` (file SHA `bcdde71e94`, unchanged on tip
 * `aaa30588f7`). Study source set: [FclMealBasal] is commonMain and has no mocks, so the tests live
 * in `commonTest` (`kotlin.test`). Assertions are the same locks as the Truth/JUnit reference corpus.
 */
class FclMealBasalTest {

    private fun rate(
        fclNoteActive: Boolean = true,
        sportNoteActive: Boolean = false,
        tempTargetSet: Boolean = true,
        targetBgMgdl: Double = 80.0,
        mealModesMaxBasalUph: Double = 10.0,
        profileMaxBasalUph: Double = 7.0,
        profileBasalUph: Double = 1.2,
        bgMgdl: Double = 140.0,
        deltaMgdl5m: Double = 2.0,
    ) = FclMealBasal.rateUph(
        fclNoteActive = fclNoteActive,
        sportNoteActive = sportNoteActive,
        tempTargetSet = tempTargetSet,
        targetBgMgdl = targetBgMgdl,
        mealModesMaxBasalUph = mealModesMaxBasalUph,
        profileMaxBasalUph = profileMaxBasalUph,
        profileBasalUph = profileBasalUph,
        bgMgdl = bgMgdl,
        deltaMgdl5m = deltaMgdl5m,
    )

    // ----- what the mode is for -----

    @Test
    fun anFclNoteWithALowTempTargetAsksForTheMealCeiling() {
        assertEquals(10.0, rate())
    }

    // ----- both manual acts are needed -----

    @Test
    fun withoutTheNoteNothingHappens() {
        assertNull(rate(fclNoteActive = false))
    }

    @Test
    fun withoutATempTargetNothingHappens() {
        assertNull(rate(tempTargetSet = false))
    }

    @Test
    fun aTempTargetOverTheCeilingIsNotAnFclTarget() {
        assertNull(rate(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL + 0.1))
        assertNull(rate(targetBgMgdl = 100.0))
    }

    @Test
    fun aTempTargetAtTheCeilingStillCounts() {
        assertEquals(10.0, rate(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL))
    }

    // ----- a declaration that contradicts it wins -----

    /** Two manual notes that disagree: the one that withholds insulin is the one to trust. */
    @Test
    fun aSportNoteStopsIt() {
        assertNull(rate(sportNoteActive = true))
    }

    // ----- the same two stand-downs the declared-meal floor uses -----

    @Test
    fun glucoseUnderTheFloorStopsIt() {
        assertNull(rate(bgMgdl = FclMealBasal.MIN_GLUCOSE_MGDL - 0.1))
    }

    @Test
    fun glucoseAtTheFloorStillCounts() {
        assertEquals(10.0, rate(bgMgdl = FclMealBasal.MIN_GLUCOSE_MGDL))
    }

    @Test
    fun aFastFallStopsIt() {
        assertNull(rate(deltaMgdl5m = FclMealBasal.MAX_FALL_MGDL_PER_5MIN))
        assertNull(rate(deltaMgdl5m = -9.0))
    }

    @Test
    fun aSlowFallDoesNotStopIt() {
        assertEquals(10.0, rate(deltaMgdl5m = FclMealBasal.MAX_FALL_MGDL_PER_5MIN + 0.1))
    }

    // ----- the rate it asks for -----

    @Test
    fun anUnsetMealCeilingFallsBackToTheProfileMaximum() {
        assertEquals(7.0, rate(mealModesMaxBasalUph = 0.0))
    }

    /** A floor must never pull a rate down, whatever the settings say. */
    @Test
    fun aCeilingUnderTheProfileBasalNeverLowersTheRate() {
        assertEquals(
            1.2,
            rate(mealModesMaxBasalUph = 0.4, profileMaxBasalUph = 0.5, profileBasalUph = 1.2),
        )
    }

    @Test
    fun noUsableCeilingAtAllStopsIt() {
        assertNull(rate(mealModesMaxBasalUph = 0.0, profileMaxBasalUph = 0.0, profileBasalUph = 0.0))
    }

    // ----- the arming half, shared with the callers that are not the basal floor -----

    /**
     * Three other places need to know "is an FCL meal declared right now" without asking for a rate:
     * the terminal-invariants exemption, the Autodrive gate, and the one-shot prebolus. They must not
     * each re-spell the gate, or they will drift apart.
     */
    private fun declared(
        fclNoteActive: Boolean = true,
        sportNoteActive: Boolean = false,
        tempTargetSet: Boolean = true,
        targetBgMgdl: Double = 80.0,
    ) = FclMealBasal.declared(
        fclNoteActive = fclNoteActive,
        sportNoteActive = sportNoteActive,
        tempTargetSet = tempTargetSet,
        targetBgMgdl = targetBgMgdl,
    )

    @Test
    fun aNoteWithALowTempTargetIsADeclaredFclMeal() {
        assertTrue(declared())
    }

    @Test
    fun withoutTheNoteNothingIsDeclared() {
        assertFalse(declared(fclNoteActive = false))
    }

    @Test
    fun withoutATempTargetNothingIsDeclared() {
        assertFalse(declared(tempTargetSet = false))
    }

    @Test
    fun aTempTargetOverTheCeilingIsNotADeclaredFclMeal() {
        assertFalse(declared(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL + 0.1))
    }

    @Test
    fun aTempTargetAtTheCeilingIsADeclaredFclMeal() {
        assertTrue(declared(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL))
    }

    @Test
    fun aSportNoteUndeclaresIt() {
        assertFalse(declared(sportNoteActive = true))
    }

    @Test
    fun aTargetThatIsNotAUsableNumberIsNotADeclaredFclMeal() {
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFalse(declared(targetBgMgdl = x))
        }
    }

    /** The rate can only be asked for when the meal is declared: one gate, not two. */
    @Test
    fun aRateIsNeverReturnedWhenNothingIsDeclared() {
        for (note in listOf(true, false)) {
            for (sport in listOf(true, false)) {
                for (tt in listOf(true, false)) {
                    for (target in listOf(80.0, 100.0)) {
                        val armed = declared(note, sport, tt, target)
                        val r = rate(fclNoteActive = note, sportNoteActive = sport, tempTargetSet = tt, targetBgMgdl = target)
                        if (!armed) assertNull(r)
                    }
                }
            }
        }
    }

    // ----- numbers that are not numbers -----

    @Test
    fun anyInputThatIsNotAUsableNumberStopsIt() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (x in bad) {
            assertNull(rate(targetBgMgdl = x))
            assertNull(rate(mealModesMaxBasalUph = x))
            assertNull(rate(profileMaxBasalUph = x, mealModesMaxBasalUph = 0.0))
            assertNull(rate(profileBasalUph = x))
            assertNull(rate(bgMgdl = x))
            assertNull(rate(deltaMgdl5m = x))
        }
    }

    @Test
    fun itNeverAsksForZeroOrLess() {
        for (ceiling in listOf(-5.0, 0.0, 0.1, 10.0)) {
            for (bg in listOf(60.0, 80.0, 200.0)) {
                for (d in listOf(-9.0, -3.0, 0.0, 6.0)) {
                    val r = rate(mealModesMaxBasalUph = ceiling, bgMgdl = bg, deltaMgdl5m = d)
                    if (r != null) assertTrue(r > 0.0)
                }
            }
        }
    }
}
