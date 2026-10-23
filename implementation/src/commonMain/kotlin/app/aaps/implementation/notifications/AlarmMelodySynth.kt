package app.aaps.implementation.notifications

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Renders an [AlarmMelody] to 16-bit mono PCM, and wraps it as a WAV file.
 *
 * Soft by design: pure tones with a few quiet overtones, smooth attacks, a peak at [MAX_AMPLITUDE] of
 * full scale. Every note ends before the clip ends, so the clip starts and ends in silence and the
 * looping player does not click.
 *
 * Pure Kotlin in common code, so both phones play exactly the same samples: Android plays the WAV
 * from a file with `MediaPlayer`, iOS plays it from memory with `AVAudioPlayer`. It is also tested
 * on both.
 */
object AlarmMelodySynth {

    const val SAMPLE_RATE = 22_050

    /** Peak level of a rendered melody, as a share of full scale. */
    const val MAX_AMPLITUDE = 0.6

    private const val RELEASE_MS = 80.0

    /** A partial of a timbre: frequency ratio, level, and how much faster it decays than the note. */
    private data class Partial(val ratio: Double, val level: Double, val decaySpeed: Double = 1.0)

    private data class Timbre(val partials: List<Partial>, val attackMs: Double, val decayTauS: Double)

    private data class Note(val startMs: Double, val freqHz: Double, val durationMs: Double)

    private val BELL = Timbre(listOf(Partial(1.0, 1.0), Partial(2.0, 0.30, 1.6), Partial(3.0, 0.12, 2.2)), attackMs = 8.0, decayTauS = 0.9)
    private val WARM = Timbre(listOf(Partial(1.0, 1.0), Partial(2.0, 0.20, 1.4)), attackMs = 15.0, decayTauS = 1.4)
    private val WOOD = Timbre(listOf(Partial(1.0, 1.0), Partial(4.0, 0.25, 4.0)), attackMs = 3.0, decayTauS = 0.35)
    private val SOFT_TONE = Timbre(listOf(Partial(1.0, 1.0), Partial(2.0, 0.08)), attackMs = 120.0, decayTauS = 4.0)

    private fun score(melody: AlarmMelody): Pair<Timbre, List<Note>> = when (melody) {
        AlarmMelody.SOFT_CHIME   -> BELL to listOf(
            Note(0.0, 523.25, 1_800.0), Note(350.0, 659.25, 1_800.0), Note(700.0, 783.99, 1_800.0), Note(1_050.0, 1_046.50, 1_800.0),
        )

        AlarmMelody.WARM_BELLS   -> WARM to listOf(
            Note(0.0, 440.00, 2_400.0), Note(600.0, 523.25, 2_400.0), Note(1_200.0, 659.25, 2_400.0),
        )

        AlarmMelody.MARIMBA      -> WOOD to listOf(
            Note(0.0, 392.00, 700.0), Note(250.0, 493.88, 700.0), Note(500.0, 587.33, 700.0), Note(750.0, 783.99, 700.0),
            Note(1_250.0, 587.33, 700.0), Note(1_500.0, 783.99, 700.0),
        )

        AlarmMelody.GENTLE_PULSE -> SOFT_TONE to listOf(
            Note(0.0, 659.25, 400.0), Note(600.0, 659.25, 400.0), Note(1_200.0, 659.25, 400.0),
        )
    }

    fun render(melody: AlarmMelody): ShortArray {
        val total = (melody.durationMs * SAMPLE_RATE / 1000).toInt()
        val mix = DoubleArray(total)
        val (timbre, notes) = score(melody)
        for (note in notes) {
            val start = (note.startMs * SAMPLE_RATE / 1000).toInt()
            val length = (note.durationMs * SAMPLE_RATE / 1000).toInt()
            for (i in 0 until length) {
                val index = start + i
                if (index >= total) break
                val tMs = i * 1000.0 / SAMPLE_RATE
                val tS = tMs / 1000.0
                val attack = if (tMs < timbre.attackMs) tMs / timbre.attackMs else 1.0
                val remainingMs = note.durationMs - tMs
                val release = if (remainingMs < RELEASE_MS) 0.5 - 0.5 * cos(PI * remainingMs / RELEASE_MS) else 1.0
                var sample = 0.0
                for (p in timbre.partials) {
                    val decay = exp(-tS * p.decaySpeed / timbre.decayTauS)
                    sample += p.level * decay * sin(2.0 * PI * note.freqHz * p.ratio * tS)
                }
                mix[index] += sample * attack * release
            }
        }
        val peak = mix.maxOf { abs(it) }.takeIf { it > 0.0 } ?: 1.0
        val scale = MAX_AMPLITUDE * Short.MAX_VALUE / peak
        return ShortArray(total) { (mix[it] * scale).toInt().toShort() }
    }

    /** A 44-byte RIFF/WAVE header (PCM, mono, 16-bit, [SAMPLE_RATE]) followed by [pcm], little-endian. */
    fun toWav(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)
        fun putAscii(at: Int, text: String) = text.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun putInt(at: Int, value: Int) {
            for (b in 0 until 4) out[at + b] = (value shr (8 * b)).toByte()
        }

        fun putShort(at: Int, value: Int) {
            out[at] = value.toByte()
            out[at + 1] = (value shr 8).toByte()
        }
        putAscii(0, "RIFF"); putInt(4, 36 + dataSize); putAscii(8, "WAVE")
        putAscii(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, SAMPLE_RATE); putInt(28, SAMPLE_RATE * 2); putShort(32, 2); putShort(34, 16)
        putAscii(36, "data"); putInt(40, dataSize)
        pcm.forEachIndexed { i, s -> putShort(44 + i * 2, s.toInt()) }
        return out
    }
}
