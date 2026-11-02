package app.aaps.plugins.sync.nsclientV3.remotecontrol

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The text the follower sends must stay what the master understands (same as dev_OAPSAIMI). */
class RemoteControlCommandsTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun mode(id: String) = ModePresets.ALL_MODES.single { it.id == id }

    private fun note(text: String, startedMinutesAgo: Int, durationMin: Int, type: TE.Type = TE.Type.NOTE) = TE(
        timestamp = now - startedMinutesAgo * minute,
        type = type,
        glucoseUnit = GlucoseUnit.MGDL,
        note = text,
        duration = durationMin * minute
    )

    @Test
    fun mealModeIsSentAsTwoNotes() {
        val notes = RemoteControlCommands.modeNotes(mode("breakfast"), 45, "4242", now)
        assertEquals(listOf("AIMI:4242 mode bfast 45", "bfast 45"), notes.map { it.note })
        assertEquals(listOf(now, now + 1000L), notes.map { it.timestamp })
        assertTrue(notes.all { it.type == TE.Type.NOTE && it.duration == 45 * minute })
    }

    @Test
    fun stopIsSentAsPlainStop() {
        val notes = RemoteControlCommands.modeNotes(mode(ModePresets.STOP_ID), 0, "4242", now)
        assertEquals(listOf("AIMI:4242 mode stop 0", "stop"), notes.map { it.note })
    }

    @Test
    fun contextModeTextIsReadByTheParser() {
        assertEquals("cardio 60min", RemoteControlCommands.contextText(mode("cardio"), 60))
        assertEquals("work stress 240min", RemoteControlCommands.contextText(mode("work_stress"), 240))
    }

    @Test
    fun sentAsNoteOnlyForMealAndControl() {
        assertTrue(ModePresets.ALL_MODES.filter { it.category.sentAsNote }.all { it.category == ModeCategory.MEAL || it.category == ModeCategory.CONTROL })
        assertEquals(ModePresets.ALL_MODES.size, ModePresets.MODES_TAB.size + ModePresets.CONTEXTS_TAB.size)
    }

    @Test
    fun activeModesReadsAimiFormAndKeywords() {
        val events = listOf(
            note("AIMI:4242 mode lunch 60", startedMinutesAgo = 10, durationMin = 60),
            note("sport 120", startedMinutesAgo = 5, durationMin = 120),
            note("work stress 240", startedMinutesAgo = 1, durationMin = 240)
        )
        val active = RemoteControlCommands.activeModes(events, now)
        assertEquals(listOf("work_stress", "sport", "lunch"), active.map { it.mode.id })
    }

    @Test
    fun aimiContextNoteShowsItsMode() {
        val text = """AIMI_CONTEXT:CTX_1:PIN:4242:{"type":"Activity","act":"CARDIO","int":"HIGH","dur":3600000}"""
        val active = RemoteControlCommands.activeModes(listOf(note(text, startedMinutesAgo = 2, durationMin = 60)), now)
        assertEquals(listOf("cardio"), active.map { it.mode.id })
    }

    @Test
    fun expiredAndNonNoteEventsAreLeftOut() {
        val events = listOf(
            note("lunch 60", startedMinutesAgo = 61, durationMin = 60),
            note("dinner 60", startedMinutesAgo = 1, durationMin = 60, type = TE.Type.ANNOUNCEMENT)
        )
        assertTrue(RemoteControlCommands.activeModes(events, now).isEmpty())
    }

    @Test
    fun newestEntryOfAModeWins() {
        val events = listOf(
            note("sport 120", startedMinutesAgo = 30, durationMin = 120),
            note("sport 60", startedMinutesAgo = 5, durationMin = 60)
        )
        val item = RemoteControlCommands.activeModes(events, now).single()
        assertEquals(now - 5 * minute, item.startedAt)
        assertEquals(55, item.remainingMinutes(now))
        assertEquals(8, item.progressPercent(now))
    }
}
