package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The FCL mode keyword.
 *
 * Unlike every other keyword, an "fcl" note counts for a minimum window even when it carries no
 * duration at all. A scenario that writes the note together with a temp target usually writes it with
 * no duration, and on 2026-09-17 that is exactly what reached the loop: only the temp target. The temp
 * target is what ends the mode, so the note only has to be recent.
 */
class TherapyFclDetectionTest {

    private fun therapyWith(note: String, ageMs: Long = 60_000L, durationMs: Long = 0L): Therapy {
        val now = System.currentTimeMillis()
        val persistenceLayer = mockk<PersistenceLayer>(relaxed = true)
        coEvery { persistenceLayer.getTherapyEventDataFromTime(any(), any()) } returns listOf(
            TE(
                timestamp = now - ageMs,
                duration = durationMs,
                type = TE.Type.NOTE,
                note = note,
                glucoseUnit = GlucoseUnit.MGDL,
            )
        )
        return Therapy(persistenceLayer).apply { updateStatesBasedOnTherapyEvents(forceRefresh = true) }
    }

    @Test
    fun `a fresh fcl note with no duration sets fclTime`() {
        assertThat(therapyWith("FCL").fclTime).isTrue()
    }

    @Test
    fun `the fcl note is matched whatever the case`() {
        assertThat(therapyWith("fcl lunch time").fclTime).isTrue()
    }

    @Test
    fun `an fcl note inside the minimum window still counts`() {
        assertThat(therapyWith("FCL", ageMs = Therapy.FCL_MIN_WINDOW_MS - 60_000L).fclTime).isTrue()
    }

    @Test
    fun `an fcl note past the minimum window does not count`() {
        assertThat(therapyWith("FCL", ageMs = Therapy.FCL_MIN_WINDOW_MS + 60_000L).fclTime).isFalse()
    }

    /** A note that carries its own longer duration keeps that duration. */
    @Test
    fun `an fcl note with a longer duration counts for that duration`() {
        val therapy = therapyWith(
            "FCL",
            ageMs = Therapy.FCL_MIN_WINDOW_MS + 60_000L,
            durationMs = 3 * 60 * 60_000L,
        )
        assertThat(therapy.fclTime).isTrue()
    }

    @Test
    fun `an unrelated note does not set fclTime`() {
        assertThat(therapyWith("Sport velo").fclTime).isFalse()
    }

    /** FCL exists to avoid the prebolus, so it must not switch a prebolus mode on. */
    @Test
    fun `the fcl note must not switch any prebolus mode on`() {
        val therapy = therapyWith("FCL")
        assertThat(therapy.fclTime).isTrue()
        assertThat(therapy.mealTime).isFalse()
        assertThat(therapy.lunchTime).isFalse()
        assertThat(therapy.dinnerTime).isFalse()
        assertThat(therapy.bfastTime).isFalse()
        assertThat(therapy.highCarbTime).isFalse()
        assertThat(therapy.snackTime).isFalse()
        assertThat(therapy.anticipTime).isFalse()
    }

    @Test
    fun `a stop note switches the fcl mode off`() {
        assertThat(therapyWith("stop").fclTime).isFalse()
    }
}
