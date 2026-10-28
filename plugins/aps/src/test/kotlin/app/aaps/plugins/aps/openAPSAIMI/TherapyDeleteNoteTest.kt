package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * A note asks to remove rows from the training corpus only when it STARTS with "delete" and is still
 * inside its duration. Before, "delete" anywhere in any note was enough, and the removal ran on every
 * tick while the note was active.
 */
class TherapyDeleteNoteTest {

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

    @Test
    fun `a note that starts with delete is reported with its time and text`() {
        val written = note("Delete 25/10/2026", ageMin = 5L, durationMin = 30L)
        val therapy = therapyWith(written)
        assertThat(therapy.deleteTime).isTrue()
        assertThat(therapy.deleteNote).isEqualTo(CorpusDeleteNote(timestamp = written.timestamp, note = "Delete 25/10/2026"))
    }

    @Test
    fun `delete inside another note is not a delete request`() {
        val therapy = therapyWith(note("lunch, will delete the old notes later", ageMin = 5L, durationMin = 30L))
        assertThat(therapy.deleteTime).isFalse()
        assertThat(therapy.deleteNote).isNull()
    }

    @Test
    fun `a delete note past its duration is not active`() {
        val therapy = therapyWith(note("delete", ageMin = 40L, durationMin = 30L))
        assertThat(therapy.deleteNote).isNull()
    }

    @Test
    fun `the newest active delete note wins`() {
        val older = note("delete 24/10/2026", ageMin = 20L, durationMin = 60L)
        val newer = note("delete", ageMin = 5L, durationMin = 60L)
        val therapy = therapyWith(older, newer)
        assertThat(therapy.deleteNote?.timestamp).isEqualTo(newer.timestamp)
        assertThat(therapy.deleteNote?.note).isEqualTo("delete")
    }
}
