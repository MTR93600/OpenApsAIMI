package app.aaps.plugins.aps.openAPSAIMI.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The FCL mode: an "fcl" note plus a low temp target force the meal basal ceiling, with no prebolus.
 *
 * The note alone must not be enough and the temp target alone must not be enough. Both are manual
 * acts, and the temp target is what ends the mode, so the pair is the whole gate.
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
        assertThat(rate()).isEqualTo(10.0)
    }

    // ----- both manual acts are needed -----

    @Test
    fun withoutTheNoteNothingHappens() {
        assertThat(rate(fclNoteActive = false)).isNull()
    }

    @Test
    fun withoutATempTargetNothingHappens() {
        assertThat(rate(tempTargetSet = false)).isNull()
    }

    @Test
    fun aTempTargetOverTheCeilingIsNotAnFclTarget() {
        assertThat(rate(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL + 0.1)).isNull()
        assertThat(rate(targetBgMgdl = 100.0)).isNull()
    }

    @Test
    fun aTempTargetAtTheCeilingStillCounts() {
        assertThat(rate(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL)).isEqualTo(10.0)
    }

    // ----- a declaration that contradicts it wins -----

    /** Two manual notes that disagree: the one that withholds insulin is the one to trust. */
    @Test
    fun aSportNoteStopsIt() {
        assertThat(rate(sportNoteActive = true)).isNull()
    }

    // ----- the same two stand-downs the declared-meal floor uses -----

    @Test
    fun glucoseUnderTheFloorStopsIt() {
        assertThat(rate(bgMgdl = FclMealBasal.MIN_GLUCOSE_MGDL - 0.1)).isNull()
    }

    @Test
    fun glucoseAtTheFloorStillCounts() {
        assertThat(rate(bgMgdl = FclMealBasal.MIN_GLUCOSE_MGDL)).isEqualTo(10.0)
    }

    @Test
    fun aFastFallStopsIt() {
        assertThat(rate(deltaMgdl5m = FclMealBasal.MAX_FALL_MGDL_PER_5MIN)).isNull()
        assertThat(rate(deltaMgdl5m = -9.0)).isNull()
    }

    @Test
    fun aSlowFallDoesNotStopIt() {
        assertThat(rate(deltaMgdl5m = FclMealBasal.MAX_FALL_MGDL_PER_5MIN + 0.1)).isEqualTo(10.0)
    }

    // ----- the rate it asks for -----

    @Test
    fun anUnsetMealCeilingFallsBackToTheProfileMaximum() {
        assertThat(rate(mealModesMaxBasalUph = 0.0)).isEqualTo(7.0)
    }

    /** A floor must never pull a rate down, whatever the settings say. */
    @Test
    fun aCeilingUnderTheProfileBasalNeverLowersTheRate() {
        assertThat(rate(mealModesMaxBasalUph = 0.4, profileMaxBasalUph = 0.5, profileBasalUph = 1.2))
            .isEqualTo(1.2)
    }

    @Test
    fun noUsableCeilingAtAllStopsIt() {
        assertThat(rate(mealModesMaxBasalUph = 0.0, profileMaxBasalUph = 0.0, profileBasalUph = 0.0)).isNull()
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
        assertThat(declared()).isTrue()
    }

    @Test
    fun withoutTheNoteNothingIsDeclared() {
        assertThat(declared(fclNoteActive = false)).isFalse()
    }

    @Test
    fun withoutATempTargetNothingIsDeclared() {
        assertThat(declared(tempTargetSet = false)).isFalse()
    }

    @Test
    fun aTempTargetOverTheCeilingIsNotADeclaredFclMeal() {
        assertThat(declared(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL + 0.1)).isFalse()
    }

    @Test
    fun aTempTargetAtTheCeilingIsADeclaredFclMeal() {
        assertThat(declared(targetBgMgdl = FclMealBasal.MAX_TEMP_TARGET_MGDL)).isTrue()
    }

    @Test
    fun aSportNoteUndeclaresIt() {
        assertThat(declared(sportNoteActive = true)).isFalse()
    }

    @Test
    fun aTargetThatIsNotAUsableNumberIsNotADeclaredFclMeal() {
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThat(declared(targetBgMgdl = x)).isFalse()
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
                        if (!armed) assertThat(r).isNull()
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
            assertThat(rate(targetBgMgdl = x)).isNull()
            assertThat(rate(mealModesMaxBasalUph = x)).isNull()
            assertThat(rate(profileMaxBasalUph = x, mealModesMaxBasalUph = 0.0)).isNull()
            assertThat(rate(profileBasalUph = x)).isNull()
            assertThat(rate(bgMgdl = x)).isNull()
            assertThat(rate(deltaMgdl5m = x)).isNull()
        }
    }

    @Test
    fun itNeverAsksForZeroOrLess() {
        for (ceiling in listOf(-5.0, 0.0, 0.1, 10.0)) {
            for (bg in listOf(60.0, 80.0, 200.0)) {
                for (d in listOf(-9.0, -3.0, 0.0, 6.0)) {
                    val r = rate(mealModesMaxBasalUph = ceiling, bgMgdl = bg, deltaMgdl5m = d)
                    if (r != null) assertThat(r).isGreaterThan(0.0)
                }
            }
        }
    }
}
