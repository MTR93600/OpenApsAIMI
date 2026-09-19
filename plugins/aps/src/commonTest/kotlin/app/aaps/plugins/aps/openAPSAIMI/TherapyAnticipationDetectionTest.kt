package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The declared-meal anticipation mode. A note holding "anticip" while still inside its own duration
 * must set [Therapy.anticipTime].
 *
 * The keyword matters. `findActiveMealEvents` matches any note containing "meal", so a word such as
 * "premeal" would switch the meal mode on as well — and with it its 2.0 U prebolus, which is the one
 * thing this mode exists to avoid. These tests pin that separation down.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `bce972221c` (file SHA `770a5f6fae`, unchanged on tip
 * `aaa30588f7`). Study source set: [Therapy] is commonMain with a coroutine snapshot; mockk is
 * JVM-only, so the tests drive the real snapshot path through [TherapyNotePersistence] and
 * `kotlin.test`. Names are camelCase (no backtick commas).
 */
class TherapyAnticipationDetectionTest {

    private fun therapyWith(note: String, durationMs: Long = 30 * 60_000L): Therapy {
        val now = aimiWallClockMs()
        val persistenceLayer = TherapyNotePersistence(
            listOf(
                TE(
                    timestamp = now - 60_000L,
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
    fun anticipNoteWithinDurationSetsAnticipTime() {
        assertTrue(therapyWith("anticip 40g").anticipTime)
    }

    @Test
    fun anticipNoteIsMatchedWhateverTheCase() {
        assertTrue(therapyWith("ANTICIP").anticipTime)
    }

    @Test
    fun anticipNotePastItsDurationDoesNotSetAnticipTime() {
        assertFalse(therapyWith("anticip", durationMs = 30_000L).anticipTime)
    }

    @Test
    fun anUnrelatedNoteDoesNotSetAnticipTime() {
        assertFalse(therapyWith("Sport velo").anticipTime)
    }

    @Test
    fun theAnticipNoteMustNotSwitchTheMealModeOn() {
        val therapy = therapyWith("anticip 40g")
        assertTrue(therapy.anticipTime)
        assertFalse(therapy.mealTime)
        assertFalse(therapy.lunchTime)
        assertFalse(therapy.dinnerTime)
        assertFalse(therapy.bfastTime)
    }

    @Test
    fun aMealNoteDoesNotSwitchTheAnticipationModeOn() {
        val therapy = therapyWith("meal")
        assertTrue(therapy.mealTime)
        assertFalse(therapy.anticipTime)
    }
}
