package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.plugins.aps.openAPSAIMI.basal.FclMealBasal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Garmin `/mode` writes is what the clinical tick already reads.
 *
 * Source: `origin/dev_OAPSAIMI` @ `166ddb6db0`. The NOTE text is the keyword only (`sport`, `fcl`).
 * The window is `TherapyEvent.duration` (sport default 120 minutes). `fcl` also posts a temporary
 * target of 80 mg/dL for 30 minutes; [FclMealBasal] treats 80 as a low target and stands down when
 * a sport note is live. Embedding the minutes in the note does not open the window.
 */
class GarminModeTherapyNoteTest {

    private fun therapy(note: String, ageMs: Long, durationMs: Long): Therapy {
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
    fun aKeywordOnlySportNoteArmsSportFor120Minutes() {
        val durationMs = 120L * 60_000L
        val live = therapy("sport", ageMs = 60_000L, durationMs = durationMs)
        assertTrue(live.sportTime)
        assertFalse(live.fclTime)

        val stillLive = therapy("sport", ageMs = durationMs - 1_000L, durationMs = durationMs)
        assertTrue(stillLive.sportTime)

        val expired = therapy("sport", ageMs = durationMs + 60_000L, durationMs = durationMs)
        assertFalse(expired.sportTime)
    }

    @Test
    fun minutesEmbeddedInTheSportNoteDoNotOpenTheWindow() {
        val therapy = therapy("sport 120", ageMs = 60_000L, durationMs = 0L)
        assertFalse(therapy.sportTime)
    }

    @Test
    fun aKeywordOnlyFclNoteArmsFclAndNotSport() {
        val therapy = therapy("fcl", ageMs = 60_000L, durationMs = 30L * 60_000L)
        assertTrue(therapy.fclTime)
        assertFalse(therapy.sportTime)
    }

    @Test
    fun theFclCompanionTargetOf80ArmsTheMealCeilingUnlessSportIsLive() {
        assertTrue(80.0 <= FclMealBasal.MAX_TEMP_TARGET_MGDL)
        assertTrue(
            FclMealBasal.declared(
                fclNoteActive = true,
                sportNoteActive = false,
                tempTargetSet = true,
                targetBgMgdl = 80.0,
            )
        )
        assertFalse(
            FclMealBasal.declared(
                fclNoteActive = true,
                sportNoteActive = true,
                tempTargetSet = true,
                targetBgMgdl = 80.0,
            )
        )
    }
}
