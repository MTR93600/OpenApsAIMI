package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The declared-meal anticipation mode. A note holding "anticip" while still inside its own duration
 * must set [Therapy.anticipTime].
 *
 * The keyword matters. `findActiveMealEvents` matches any note containing "meal", so a word such as
 * "premeal" would switch the meal mode on as well — and with it its 2.0 U prebolus, which is the one
 * thing this mode exists to avoid. These tests pin that separation down.
 */
class TherapyAnticipationDetectionTest {

    private fun therapyWith(note: String, durationMs: Long = 30 * 60_000L): Therapy {
        val now = System.currentTimeMillis()
        val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns listOf(
            TE(
                timestamp = now - 60_000L,
                duration = durationMs,
                type = TE.Type.NOTE,
                note = note,
                glucoseUnit = GlucoseUnit.MGDL,
            )
        )
        return Therapy(persistenceLayer).apply { updateStatesBasedOnTherapyEvents(forceRefresh = true) }
    }

    @Test
    fun `anticip note within duration sets anticipTime`() {
        assertThat(therapyWith("anticip 40g").anticipTime).isTrue()
    }

    @Test
    fun `anticip note is matched whatever the case`() {
        assertThat(therapyWith("ANTICIP").anticipTime).isTrue()
    }

    @Test
    fun `anticip note past its duration does not set anticipTime`() {
        assertThat(therapyWith("anticip", durationMs = 30_000L).anticipTime).isFalse()
    }

    @Test
    fun `an unrelated note does not set anticipTime`() {
        assertThat(therapyWith("Sport velo").anticipTime).isFalse()
    }

    @Test
    fun `the anticip note must not switch the meal mode on`() {
        val therapy = therapyWith("anticip 40g")
        assertThat(therapy.anticipTime).isTrue()
        assertThat(therapy.mealTime).isFalse()
        assertThat(therapy.lunchTime).isFalse()
        assertThat(therapy.dinnerTime).isFalse()
        assertThat(therapy.bfastTime).isFalse()
    }

    @Test
    fun `a meal note does not switch the anticipation mode on`() {
        val therapy = therapyWith("meal")
        assertThat(therapy.mealTime).isTrue()
        assertThat(therapy.anticipTime).isFalse()
    }
}
