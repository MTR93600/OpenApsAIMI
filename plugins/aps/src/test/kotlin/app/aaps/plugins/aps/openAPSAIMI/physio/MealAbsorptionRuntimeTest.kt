package app.aaps.plugins.aps.openAPSAIMI.physio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MealAbsorptionRuntimeTest {

    private val now = 1_000_000_000L
    private val min = 60_000L

    /** The old value read `lastActiveAtMs`, which this same tick had just set to now: always 0. */
    @Test
    fun runtimeIsTheEpisodeAgeNotTheLastActiveTick() {
        val r = MealAbsorptionRuntime.resolve(now, onsetAtMs = now - 130 * min, lastActiveAtMs = now, modeRuntimesMin = emptyList())
        assertThat(r.runtimeMin).isEqualTo(130)
        assertThat(r.legacyMin).isEqualTo(0)
        assertThat(r.onsetMin).isEqualTo(130)
        assertThat(r.usedOnset).isTrue()
    }

    @Test
    fun withoutEpisodeFallsBackToTheModeRuntimes() {
        val r = MealAbsorptionRuntime.resolve(now, onsetAtMs = 0L, lastActiveAtMs = 0L, modeRuntimesMin = listOf(0L, 45L))
        assertThat(r.runtimeMin).isEqualTo(45)
        assertThat(r.onsetMin).isNull()
        assertThat(r.usedOnset).isFalse()
    }

    @Test
    fun clockGoingBackNeverGivesANegativeAge() {
        val r = MealAbsorptionRuntime.resolve(now, onsetAtMs = now + 5 * min, lastActiveAtMs = now + 5 * min, modeRuntimesMin = emptyList())
        assertThat(r.runtimeMin).isEqualTo(0)
        assertThat(r.legacyMin).isEqualTo(0)
    }
}
