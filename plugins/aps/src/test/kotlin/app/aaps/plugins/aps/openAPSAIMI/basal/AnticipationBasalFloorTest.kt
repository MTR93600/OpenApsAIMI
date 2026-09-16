package app.aaps.plugins.aps.openAPSAIMI.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The declared-meal anticipation floor.
 *
 * The user says "I am eating" before anything shows on the sensor, and the loop spends a fixed
 * budget of insulin as a basal floor over a short window instead of waiting for the rise to confirm
 * a meal. This is the only gesture in the project that **raises** a dose, so every test here is also
 * a test of a bound: the window, the budget, the pump ceiling, and the two states in which it must
 * stand down.
 */
class AnticipationBasalFloorTest {

    private val profile = 0.60
    private val maxBasal = 10.0

    private fun floor(
        budgetU: Double = 2.0,
        elapsedMinutes: Double = 5.0,
        profileBasalUph: Double = profile,
        maxBasalUph: Double = maxBasal,
        bgMgdl: Double = 110.0,
        deltaMgdl5m: Double = 0.5,
    ) = AnticipationBasalFloor.floorRateUph(
        budgetU = budgetU,
        elapsedMinutes = elapsedMinutes,
        profileBasalUph = profileBasalUph,
        maxBasalUph = maxBasalUph,
        bgMgdl = bgMgdl,
        deltaMgdl5m = deltaMgdl5m,
    )

    // ----- the rate -----

    @Test
    fun theBudgetIsSpreadEvenlyOverTheWindow() {
        // 2 U over 30 minutes is 4 U/h on top of the profile.
        assertThat(floor(budgetU = 2.0)).isWithin(1e-9).of(0.60 + 4.0)
    }

    @Test
    fun theRateDoesNotChangeAsTheWindowRunsDown() {
        // A flat rate for the whole window is what makes the total equal the budget.
        assertThat(floor(elapsedMinutes = 1.0)).isEqualTo(floor(elapsedMinutes = 29.0))
    }

    @Test
    fun theRateNeverPassesThePumpCeiling() {
        assertThat(floor(budgetU = 9.0, maxBasalUph = 7.0)).isWithin(1e-9).of(7.0)
    }

    @Test
    fun theRateIsNeverBelowTheProfile() {
        assertThat(floor(budgetU = 2.0, maxBasalUph = 0.10)).isAtLeast(profile)
    }

    // ----- the window -----

    @Test
    fun pastTheWindowThereIsNoFloor() {
        assertThat(floor(elapsedMinutes = AnticipationBasalFloor.WINDOW_MINUTES + 0.1)).isNull()
    }

    @Test
    fun atTheLastMinuteOfTheWindowTheFloorStillStands() {
        assertThat(floor(elapsedMinutes = AnticipationBasalFloor.WINDOW_MINUTES)).isNotNull()
    }

    @Test
    fun aClockThatMovedBackGivesNoFloor() {
        assertThat(floor(elapsedMinutes = -1.0)).isNull()
    }

    // ----- the budget -----

    @Test
    fun noBudgetMeansNoFloor() {
        assertThat(floor(budgetU = 0.0)).isNull()
        assertThat(floor(budgetU = -1.0)).isNull()
    }

    // ----- the two states where it must stand down -----

    @Test
    fun belowTheGlucoseGuardThereIsNoFloor() {
        assertThat(floor(bgMgdl = AnticipationBasalFloor.MIN_GLUCOSE_MGDL - 0.1)).isNull()
    }

    @Test
    fun atTheGlucoseGuardTheFloorStillStands() {
        assertThat(floor(bgMgdl = AnticipationBasalFloor.MIN_GLUCOSE_MGDL)).isNotNull()
    }

    @Test
    fun whileGlucoseIsFallingFastThereIsNoFloor() {
        assertThat(floor(deltaMgdl5m = AnticipationBasalFloor.MAX_FALL_MGDL_PER_5MIN)).isNull()
        assertThat(floor(deltaMgdl5m = -10.0)).isNull()
    }

    @Test
    fun aGentleFallDoesNotStandItDown() {
        assertThat(floor(deltaMgdl5m = -1.0)).isNotNull()
    }

    // ----- numbers that are not numbers -----

    @Test
    fun anyInputThatIsNotAUsableNumberGivesNoFloor() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (x in bad) {
            assertThat(floor(budgetU = x)).isNull()
            assertThat(floor(elapsedMinutes = x)).isNull()
            assertThat(floor(profileBasalUph = x)).isNull()
            assertThat(floor(maxBasalUph = x)).isNull()
            assertThat(floor(bgMgdl = x)).isNull()
            assertThat(floor(deltaMgdl5m = x)).isNull()
        }
    }

    // ----- what the whole window costs -----

    @Test
    fun theWholeWindowDeliversTheBudgetAndNotMore() {
        val budget = 2.0
        val rate = floor(budgetU = budget)!!
        val deliveredU = rate * (AnticipationBasalFloor.WINDOW_MINUTES / 60.0)
        val profileWouldHaveGiven = profile * (AnticipationBasalFloor.WINDOW_MINUTES / 60.0)
        assertThat(deliveredU - profileWouldHaveGiven).isWithin(1e-9).of(budget)
    }
}
