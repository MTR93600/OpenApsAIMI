package app.aaps.implementation.notifications

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Generated alarm melodies: soft, no click at the loop point, a valid WAV file.
 * In commonTest so the same samples are checked on the JVM and on the iOS simulator.
 */
class AlarmMelodySynthTest {

    @Test
    fun everyMelodyHasItsLengthAndStaysSoft() {
        for (melody in AlarmMelody.entries) {
            val pcm = AlarmMelodySynth.render(melody)
            assertEquals((melody.durationMs * AlarmMelodySynth.SAMPLE_RATE / 1000).toInt(), pcm.size)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue(peak > 1000, "audible: $melody peak $peak")
            assertTrue(peak <= (AlarmMelodySynth.MAX_AMPLITUDE * Short.MAX_VALUE).toInt() + 1, "never harsh: $melody peak $peak")
        }
    }

    /** The player loops the clip: it must start and end in silence, or the loop clicks. */
    @Test
    fun clipStartsAndEndsSilent() {
        for (melody in AlarmMelody.entries) {
            val pcm = AlarmMelodySynth.render(melody)
            assertTrue(abs(pcm.first().toInt()) < 50, "$melody first sample ${pcm.first()}")
            assertTrue(abs(pcm.last().toInt()) < 50, "$melody last sample ${pcm.last()}")
        }
    }

    @Test
    fun wavHeaderIsValid() {
        val pcm = AlarmMelodySynth.render(AlarmMelody.SOFT_CHIME)
        val wav = AlarmMelodySynth.toWav(pcm)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals(44 + pcm.size * 2, wav.size)
        val dataSize = (wav[40].toInt() and 0xFF) or ((wav[41].toInt() and 0xFF) shl 8) or
            ((wav[42].toInt() and 0xFF) shl 16) or ((wav[43].toInt() and 0xFF) shl 24)
        assertEquals(pcm.size * 2, dataSize)
    }

    @Test
    fun choiceIdsRoundTrip() {
        for (melody in AlarmMelody.entries) assertEquals(melody, AlarmMelody.fromChoice(melody.id))
        assertNull(AlarmMelody.fromChoice(AlarmMelody.DEFAULT_CHOICE))
        assertNull(AlarmMelody.fromChoice("unknown"))
    }
}
