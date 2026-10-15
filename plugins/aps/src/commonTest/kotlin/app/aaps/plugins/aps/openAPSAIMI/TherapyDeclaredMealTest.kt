package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Therapy.lastDeclaredMeal]: the meal input of the PK/PD curve learning gate.
 *
 * The usual `*runtime` clocks stop at 60 min. A meal still acts on glucose three hours later, so the
 * gate needs a longer look back.
 */
class TherapyDeclaredMealTest {

    private val minute = 60_000L

    private fun therapyWith(vararg notes: Pair<String, Long>): Therapy {
        val now = aimiWallClockMs()
        val events = notes.map { (note, ageMin) ->
            TE(
                timestamp = now - ageMin * minute,
                duration = 0L,
                type = TE.Type.NOTE,
                note = note,
                glucoseUnit = GlucoseUnit.MGDL,
            )
        }
        return Therapy(TherapyNotePersistence(events)).apply { updateStatesBasedOnTherapyEvents(forceRefresh = true) }
    }

    @Test
    fun noNoteGivesNoMeal() {
        assertNull(therapyWith().lastDeclaredMeal(aimiWallClockMs()))
    }

    @Test
    fun aLunchNoteTwoHoursAgoIsFoundEvenThoughTheRuntimeClockStopsAtOneHour() {
        val therapy = therapyWith("lunch" to 120L)
        val meal = therapy.lastDeclaredMeal(aimiWallClockMs())
        assertNotNull(meal)
        assertTrue(meal.ageMin in 119L..121L)
        assertFalse(meal.highCarb)
    }

    @Test
    fun theNewestMealWins() {
        val therapy = therapyWith("dinner" to 200L, "snack" to 30L)
        val meal = therapy.lastDeclaredMeal(aimiWallClockMs())
        assertNotNull(meal)
        assertTrue(meal.ageMin in 29L..31L)
    }

    @Test
    fun aHighCarbNoteIsMarkedHighCarb() {
        val meal = therapyWith("highcarb" to 10L).lastDeclaredMeal(aimiWallClockMs())
        assertNotNull(meal)
        assertTrue(meal.highCarb)
    }

    @Test
    fun anAnticipNoteCountsAsADeclaredMeal() {
        assertNotNull(therapyWith("anticip" to 5L).lastDeclaredMeal(aimiWallClockMs()))
    }

    @Test
    fun aNoteOlderThanTheLookbackIsIgnored() {
        val therapy = therapyWith("lunch" to Therapy.DECLARED_MEAL_LOOKBACK_MIN + 10L)
        assertNull(therapy.lastDeclaredMeal(aimiWallClockMs()))
    }

    @Test
    fun aNoteThatIsNotAMealIsIgnored() {
        assertNull(therapyWith("sport" to 10L).lastDeclaredMeal(aimiWallClockMs()))
    }

    @Test
    fun theAgeIsRelativeToTheTimeAskedFor() {
        val now = aimiWallClockMs()
        val meal = therapyWith("meal" to 0L).lastDeclaredMeal(now + 30 * minute)
        assertEquals(30L, meal?.ageMin)
    }
}
