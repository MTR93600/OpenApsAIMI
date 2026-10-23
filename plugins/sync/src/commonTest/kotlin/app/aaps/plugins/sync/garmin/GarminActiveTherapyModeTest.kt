package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The `mode` label of Garmin `/sgv.json`.
 *
 * Ported from `origin/dev_OAPSAIMI` `LoopHubTest.testDetectActiveTherapyMode_Coffee` and
 * `testDetectActiveTherapyMode_MealWinsOverCoffee` (`e903113af8`, `60010166bb`). On the reference
 * the rule sits in `LoopHubImpl`; here it is the pure [GarminActiveTherapyMode], so the tests live
 * in `commonTest`.
 */
class GarminActiveTherapyModeTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L

    private fun note(text: String, atMs: Long, durationMin: Long) =
        TE(timestamp = atMs, type = TE.Type.NOTE, glucoseUnit = GlucoseUnit.MGDL, note = text, duration = durationMin * min)

    /** Same window as the loop: the note's duration, 90 min without one, never more than 150 min. */
    @Test
    fun coffeeUsesTheLoopWindow() {
        assertEquals("COFFEE", GarminActiveTherapyMode.detect(listOf(note("coffee", t0, 0)), t0 + 89 * min))
        assertNull(GarminActiveTherapyMode.detect(listOf(note("coffee", t0, 0)), t0 + 91 * min))
        assertEquals("COFFEE", GarminActiveTherapyMode.detect(listOf(note("café", t0, 120)), t0 + 119 * min))
        assertNull(GarminActiveTherapyMode.detect(listOf(note("coffee", t0, 300)), t0 + 151 * min))
    }

    /** A declared meal wins over coffee, as in the loop. */
    @Test
    fun mealWinsOverCoffee() {
        val events = listOf(note("coffee", t0, 90), note("bfast", t0, 60))
        assertEquals("BFAST", GarminActiveTherapyMode.detect(events, t0 + 10 * min))
    }

    /** FCL notes often have duration 0: they stay active for at least one hour. */
    @Test
    fun fclWithoutDurationLastsOneHour() {
        assertEquals("FCL", GarminActiveTherapyMode.detect(listOf(note("fcl", t0, 0)), t0 + 59 * min))
        assertNull(GarminActiveTherapyMode.detect(listOf(note("fcl", t0, 0)), t0 + 61 * min))
    }

    /** Sport has the highest priority. */
    @Test
    fun sportWinsOverFcl() {
        val events = listOf(note("fcl", t0, 30), note("sport", t0, 120))
        assertEquals("SPORT", GarminActiveTherapyMode.detect(events, t0 + 10 * min))
    }

    /** An AIMI context intent is not a mode the person set, even when its JSON names a meal. */
    @Test
    fun contextNotesAreIgnored() {
        val events = listOf(note("""AIMI_CONTEXT:abc:{"type":"lunch"}""", t0, 60))
        assertNull(GarminActiveTherapyMode.detect(events, t0 + 10 * min))
    }

    @Test
    fun expiredOrMissingNotesGiveNoMode() {
        assertNull(GarminActiveTherapyMode.detect(emptyList(), t0))
        assertNull(GarminActiveTherapyMode.detect(listOf(note("lunch", t0, 60)), t0 + 61 * min))
    }
}
