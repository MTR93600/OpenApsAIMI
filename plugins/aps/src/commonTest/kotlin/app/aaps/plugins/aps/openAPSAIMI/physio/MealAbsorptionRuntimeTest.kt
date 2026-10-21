package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Source: `dev_OAPSAIMI` working tree 2026-10-21 `MealAbsorptionRuntimeTest`, same cases in `kotlin.test`. */
class MealAbsorptionRuntimeTest {

    private val now = 1_000_000_000L
    private val min = 60_000L

    /** The old value read `lastActiveAtMs`, which this same tick had just set to now: always 0. */
    @Test
    fun runtimeIsTheEpisodeAgeNotTheLastActiveTick() {
        val r = MealAbsorptionRuntime.resolve(now, onsetAtMs = now - 130 * min, lastActiveAtMs = now, modeRuntimesMin = emptyList())
        assertEquals(130, r.runtimeMin)
        assertEquals(0, r.legacyMin)
        assertEquals(130, r.onsetMin)
        assertTrue(r.usedOnset)
    }

    @Test
    fun withoutEpisodeFallsBackToTheModeRuntimes() {
        val r = MealAbsorptionRuntime.resolve(now, onsetAtMs = 0L, lastActiveAtMs = 0L, modeRuntimesMin = listOf(0L, 45L))
        assertEquals(45, r.runtimeMin)
        assertNull(r.onsetMin)
        assertFalse(r.usedOnset)
    }

    @Test
    fun clockGoingBackNeverGivesANegativeAge() {
        val r = MealAbsorptionRuntime.resolve(now, onsetAtMs = now + 5 * min, lastActiveAtMs = now + 5 * min, modeRuntimesMin = emptyList())
        assertEquals(0, r.runtimeMin)
        assertEquals(0, r.legacyMin)
    }
}
