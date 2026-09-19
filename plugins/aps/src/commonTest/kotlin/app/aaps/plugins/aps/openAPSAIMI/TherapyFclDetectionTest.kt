package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The FCL mode keyword.
 *
 * Unlike every other keyword, an "fcl" note counts for a minimum window even when it carries no
 * duration at all. A scenario that writes the note together with a temp target usually writes it with
 * no duration, and on 2026-09-17 that is exactly what reached the loop: only the temp target. The temp
 * target is what ends the mode, so the note only has to be recent.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `3dd116826a` (file SHA `6a984e58c9`, unchanged on tip
 * `aaa30588f7`). Study source set: [Therapy] is commonMain with a coroutine snapshot; mockk is
 * JVM-only, so the tests drive the real snapshot path through [TherapyNotePersistence] and
 * `kotlin.test`. Names are camelCase (no backtick commas).
 */
class TherapyFclDetectionTest {

    private fun therapyWith(note: String, ageMs: Long = 60_000L, durationMs: Long = 0L): Therapy {
        val now = aimiWallClockMs()
        val persistenceLayer = TherapyNotePersistence(
            listOf(
                TE(
                    timestamp = now - ageMs,
                    duration = durationMs,
                    type = TE.Type.NOTE,
                    note = note,
                    glucoseUnit = GlucoseUnit.MGDL,
                )
            )
        )
        return Therapy(persistenceLayer).apply { updateStatesBasedOnTherapyEvents(forceRefresh = true) }
    }

    @Test
    fun aFreshFclNoteWithNoDurationSetsFclTime() {
        assertTrue(therapyWith("FCL").fclTime)
    }

    @Test
    fun theFclNoteIsMatchedWhateverTheCase() {
        assertTrue(therapyWith("fcl lunch time").fclTime)
    }

    @Test
    fun anFclNoteInsideTheMinimumWindowStillCounts() {
        assertTrue(therapyWith("FCL", ageMs = Therapy.FCL_MIN_WINDOW_MS - 60_000L).fclTime)
    }

    @Test
    fun anFclNotePastTheMinimumWindowDoesNotCount() {
        assertFalse(therapyWith("FCL", ageMs = Therapy.FCL_MIN_WINDOW_MS + 60_000L).fclTime)
    }

    /** A note that carries its own longer duration keeps that duration. */
    @Test
    fun anFclNoteWithALongerDurationCountsForThatDuration() {
        val therapy = therapyWith(
            "FCL",
            ageMs = Therapy.FCL_MIN_WINDOW_MS + 60_000L,
            durationMs = 3 * 60 * 60_000L,
        )
        assertTrue(therapy.fclTime)
    }

    @Test
    fun anUnrelatedNoteDoesNotSetFclTime() {
        assertFalse(therapyWith("Sport velo").fclTime)
    }

    /** FCL exists to avoid the prebolus, so it must not switch a prebolus mode on. */
    @Test
    fun theFclNoteMustNotSwitchAnyPrebolusModeOn() {
        val therapy = therapyWith("FCL")
        assertTrue(therapy.fclTime)
        assertFalse(therapy.mealTime)
        assertFalse(therapy.lunchTime)
        assertFalse(therapy.dinnerTime)
        assertFalse(therapy.bfastTime)
        assertFalse(therapy.highCarbTime)
        assertFalse(therapy.snackTime)
        assertFalse(therapy.anticipTime)
    }

    @Test
    fun aStopNoteSwitchesTheFclModeOff() {
        assertFalse(therapyWith("stop").fclTime)
    }
}
