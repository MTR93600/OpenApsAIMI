package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** [Therapy.lastDeclaredMeal]: the meal age the PK/PD learning gate reads, well past 60 min. */
class TherapyDeclaredMealTest {

    private val now = System.currentTimeMillis()

    private fun therapyWith(vararg notes: Pair<String, Long>): Therapy {
        val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns notes.map { (note, ageMin) ->
            TE(
                timestamp = now - ageMin * 60_000L,
                duration = 0L,
                type = TE.Type.NOTE,
                note = note,
                glucoseUnit = GlucoseUnit.MGDL,
            )
        }
        return Therapy(persistenceLayer).apply { updateStatesBasedOnTherapyEvents(forceRefresh = true) }
    }

    /** The old runtime helpers stop at 60 min; this one must still see a meal 2.5 h later. */
    @Test
    fun `an fcl note is seen past one hour`() {
        val meal = therapyWith("FCL" to 150L).lastDeclaredMeal(now)
        assertThat(meal).isNotNull()
        assertThat(meal!!.ageMin).isEqualTo(150L)
        assertThat(meal.highCarb).isFalse()
    }

    @Test
    fun `the most recent meal wins and highcarb is flagged`() {
        val meal = therapyWith("lunch" to 200L, "highcarb" to 30L).lastDeclaredMeal(now)
        assertThat(meal!!.ageMin).isEqualTo(30L)
        assertThat(meal.highCarb).isTrue()
    }

    @Test
    fun `a non-meal note or an old meal is ignored`() {
        assertThat(therapyWith("sport velo" to 10L).lastDeclaredMeal(now)).isNull()
        assertThat(therapyWith("dinner" to Therapy.DECLARED_MEAL_LOOKBACK_MIN + 5L).lastDeclaredMeal(now)).isNull()
    }
}
