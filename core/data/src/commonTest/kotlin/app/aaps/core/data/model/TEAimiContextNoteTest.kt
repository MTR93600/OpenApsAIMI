package app.aaps.core.data.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TEAimiContextNoteTest {

    private fun te(note: String?, type: TE.Type = TE.Type.NOTE) =
        TE(timestamp = 0L, type = type, note = note, glucoseUnit = GlucoseUnit.MGDL)

    @Test
    fun contextNotesAreRecognised() {
        assertTrue(te("""AIMI_CONTEXT:abc:{"type":"SlowCarbMeal"}""").isAimiContextNote())
        assertTrue(te("AIMI_CONTEXT:abc:PIN:1:{}").isAimiContextNote())
    }

    @Test
    fun personNotesAreNotContextNotes() {
        assertFalse(te("meal").isAimiContextNote())
        assertFalse(te("fcl AIMI_CONTEXT:").isAimiContextNote())
        assertFalse(te(null).isAimiContextNote())
        assertFalse(te("AIMI_CONTEXT:x", TE.Type.ANNOUNCEMENT).isAimiContextNote())
    }
}
