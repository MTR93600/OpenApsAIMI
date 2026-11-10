package app.aaps.core.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TEAimiContextNoteTest {

    private fun te(note: String?, type: TE.Type = TE.Type.NOTE) =
        TE(timestamp = 0L, type = type, note = note, glucoseUnit = GlucoseUnit.MGDL)

    @Test
    fun contextNotesAreRecognised() {
        assertThat(te("AIMI_CONTEXT:abc:{\"type\":\"SlowCarbMeal\"}").isAimiContextNote()).isTrue()
        assertThat(te("AIMI_CONTEXT:abc:PIN:1:{}").isAimiContextNote()).isTrue()
    }

    @Test
    fun personNotesAreNot() {
        assertThat(te("meal").isAimiContextNote()).isFalse()
        assertThat(te("fcl AIMI_CONTEXT:").isAimiContextNote()).isFalse()
        assertThat(te(null).isAimiContextNote()).isFalse()
        assertThat(te("AIMI_CONTEXT:x", TE.Type.ANNOUNCEMENT).isAimiContextNote()).isFalse()
    }
}
