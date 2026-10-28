package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A note asks to remove rows from the training corpus only when it STARTS with "delete" and is still
 * inside its duration. Before, "delete" anywhere in any note was enough, and the removal ran on every
 * tick while the note was active.
 *
 * Runs on the JVM and on the iOS simulator. Test names are camelCase, with no comma.
 */
class TherapyDeleteNoteTest {

    private val now = aimiWallClockMs()

    private fun note(text: String, ageMin: Long, durationMin: Long) = TE(
        timestamp = now - ageMin * 60_000L,
        duration = durationMin * 60_000L,
        type = TE.Type.NOTE,
        note = text,
        glucoseUnit = GlucoseUnit.MGDL,
    )

    private fun therapyWith(vararg notes: TE): Therapy =
        Therapy(TherapyNotePersistence(notes.toList())).apply { updateStatesBasedOnTherapyEvents(forceRefresh = true) }

    @Test
    fun aNoteThatStartsWithDeleteIsReportedWithItsTimeAndText() {
        val written = note("Delete 25/10/2026", ageMin = 5L, durationMin = 30L)
        val therapy = therapyWith(written)
        assertTrue(therapy.deleteTime)
        assertEquals(CorpusDeleteNote(timestamp = written.timestamp, note = "Delete 25/10/2026"), therapy.deleteNote)
    }

    @Test
    fun deleteInsideAnotherNoteIsNotADeleteRequest() {
        val therapy = therapyWith(note("lunch, will delete the old notes later", ageMin = 5L, durationMin = 30L))
        assertFalse(therapy.deleteTime)
        assertNull(therapy.deleteNote)
    }

    @Test
    fun aDeleteNotePastItsDurationIsNotActive() {
        val therapy = therapyWith(note("delete", ageMin = 40L, durationMin = 30L))
        assertNull(therapy.deleteNote)
    }

    @Test
    fun theNewestActiveDeleteNoteWins() {
        val older = note("delete 24/10/2026", ageMin = 20L, durationMin = 60L)
        val newer = note("delete", ageMin = 5L, durationMin = 60L)
        val therapy = therapyWith(older, newer)
        assertEquals(newer.timestamp, therapy.deleteNote?.timestamp)
        assertEquals("delete", therapy.deleteNote?.note)
    }
}
