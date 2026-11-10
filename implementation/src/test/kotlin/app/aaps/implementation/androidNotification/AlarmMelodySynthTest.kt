package app.aaps.implementation.androidNotification

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Generated alarm melodies: soft, no click at the loop point, a valid WAV file. */
class AlarmMelodySynthTest {

    @Test
    fun everyMelodyHasItsLengthAndStaysSoft() {
        for (melody in AlarmMelody.entries) {
            val pcm = AlarmMelodySynth.render(melody)
            assertThat(pcm.size).isEqualTo((melody.durationMs * AlarmMelodySynth.SAMPLE_RATE / 1000).toInt())
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertThat(peak).isGreaterThan(1000)                                   // audible
            assertThat(peak).isAtMost((AlarmMelodySynth.MAX_AMPLITUDE * Short.MAX_VALUE).toInt() + 1) // never harsh
        }
    }

    /** The player loops the clip: it must start and end in silence, or the loop clicks. */
    @Test
    fun clipStartsAndEndsSilent() {
        for (melody in AlarmMelody.entries) {
            val pcm = AlarmMelodySynth.render(melody)
            assertThat(abs(pcm.first().toInt())).isLessThan(50)
            assertThat(abs(pcm.last().toInt())).isLessThan(50)
        }
    }

    @Test
    fun wavHeaderIsValid() {
        val pcm = AlarmMelodySynth.render(AlarmMelody.SOFT_CHIME)
        val wav = AlarmMelodySynth.toWav(pcm)
        assertThat(String(wav, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(String(wav, 8, 4, Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(wav.size).isEqualTo(44 + pcm.size * 2)
        val dataSize = (wav[40].toInt() and 0xFF) or ((wav[41].toInt() and 0xFF) shl 8) or
            ((wav[42].toInt() and 0xFF) shl 16) or ((wav[43].toInt() and 0xFF) shl 24)
        assertThat(dataSize).isEqualTo(pcm.size * 2)
    }

    @Test
    fun choiceIdsRoundTrip() {
        for (melody in AlarmMelody.entries) assertThat(AlarmMelody.fromChoice(melody.id)).isEqualTo(melody)
        assertThat(AlarmMelody.fromChoice(AlarmMelody.DEFAULT_CHOICE)).isNull()
        assertThat(AlarmMelody.fromChoice("unknown")).isNull()
    }
}
