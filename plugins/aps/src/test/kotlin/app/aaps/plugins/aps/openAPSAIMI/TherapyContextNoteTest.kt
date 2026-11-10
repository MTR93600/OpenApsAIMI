package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Context intents are written as NOTE `AIMI_CONTEXT:<id>:{json}`. Their JSON holds words like "Meal" or
 * "SPORT_INTENSE", and every keyword matcher of [Therapy] used to read them as if the person had typed
 * them. On 2026-08-02 a SlowCarbMeal intent switched the legacy meal mode on: 1.5 U prebolus and 7 U/h
 * for 30 min, then BG 56. These notes must be invisible to the mode keywords.
 */
class TherapyContextNoteTest {

    private val now = System.currentTimeMillis()

    private fun note(text: String, ageMin: Long, durationMin: Long) = TE(
        timestamp = now - ageMin * 60_000L,
        duration = durationMin * 60_000L,
        type = TE.Type.NOTE,
        note = text,
        glucoseUnit = GlucoseUnit.MGDL,
    )

    private fun therapyWith(vararg notes: TE): Therapy {
        val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns notes.toList()
        return Therapy(persistenceLayer).apply { updateStatesBasedOnTherapyEvents(forceRefresh = true) }
    }

    private val slowCarb =
        """AIMI_CONTEXT:abc:{"type":"SlowCarbMeal","int":"MEDIUM","dur":18000000,"start":0,"conf":1.0}"""
    private val unannounced =
        """AIMI_CONTEXT:def:{"type":"UnannouncedMeal","dur":7200000,"start":0,"conf":0.8}"""
    private val sport =
        """AIMI_CONTEXT:ghi:{"type":"Activity","act":"SPORT_INTENSE","int":"HIGH","dur":3600000,"start":0,"conf":1.0}"""
    private val custom =
        """AIMI_CONTEXT:jkl:PIN:1234:{"type":"Custom","desc":"meal delete stop café","strat":"lunch","int":"LOW","dur":3600000,"start":0,"conf":1.0}"""

    @Test
    fun `a slow-carb or unannounced-meal intent is not a meal mode nor a declared meal`() {
        for (text in listOf(slowCarb, unannounced)) {
            val therapy = therapyWith(note(text, ageMin = 5L, durationMin = 300L))
            assertThat(therapy.mealTime).isFalse()
            assertThat(therapy.getTimeElapsedSinceLastEvent("meal")).isEqualTo(-1L)
            assertThat(therapy.lastDeclaredMeal(now)).isNull()
        }
    }

    @Test
    fun `an intense activity intent is not the sport mode`() {
        assertThat(therapyWith(note(sport, ageMin = 5L, durationMin = 60L)).sportTime).isFalse()
    }

    @Test
    fun `free text of a custom intent switches nothing`() {
        val therapy = therapyWith(note(custom, ageMin = 5L, durationMin = 60L))
        assertThat(therapy.mealTime).isFalse()
        assertThat(therapy.lunchTime).isFalse()
        assertThat(therapy.stopTime).isFalse()
        assertThat(therapy.deleteTime).isFalse()
        assertThat(therapy.activeCoffee(now)).isNull()
    }

    /** A context note posted after a real meal note must not restart the meal runtime (second prebolus). */
    @Test
    fun `the meal runtime stays on the real meal note`() {
        val therapy = therapyWith(note("meal", ageMin = 20L, durationMin = 60L), note(slowCarb, ageMin = 5L, durationMin = 300L))
        assertThat(therapy.mealTime).isTrue()
        assertThat(therapy.getTimeElapsedSinceLastEvent("meal")).isEqualTo(20L)
    }
}
