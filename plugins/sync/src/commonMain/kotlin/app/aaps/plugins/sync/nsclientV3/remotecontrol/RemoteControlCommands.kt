package app.aaps.plugins.sync.nsclientV3.remotecontrol

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE

/**
 * The text the AIMI Remote Control sends, and how it reads back what is running.
 *
 * This is the protocol between the follower and the master, the same as in dev_OAPSAIMI, so it is
 * kept apart from the screen and tested on its own.
 */
object RemoteControlCommands {

    private const val MS_PER_MINUTE = 60_000L

    /** Finds `AIMI:1234 CONTEXT SPORT 60` or `AIMI:1234 mode lunch 60`. */
    private val aimiCommand = Regex("AIMI:\\d+\\s+(CONTEXT|mode)\\s+(\\w+)(?:\\s+(\\d+))?", RegexOption.IGNORE_CASE)

    /**
     * The two notes for a meal or control mode.
     *
     * 1. `AIMI:<pin> mode <keyword> <minutes>`, kept for the history
     * 2. `<keyword> <minutes>` (or `stop`), which is the note `Therapy` on the master acts on
     *
     * The second one is one second later, so the two never share a timestamp.
     */
    fun modeNotes(mode: ModePreset, durationMin: Int, pin: String, now: Long): List<TE> {
        val durationMs = durationMin * MS_PER_MINUTE
        val carePortalText = if (mode.id == ModePresets.STOP_ID) "stop" else "${mode.therapyKeyword} $durationMin"
        return listOf(
            note(now, "AIMI:$pin mode ${mode.therapyKeyword} $durationMin", durationMs),
            note(now + 1000L, carePortalText, durationMs)
        )
    }

    /** The text for an AIMI context mode, read by the context parser. Example: `cardio 60min`. */
    fun contextText(mode: ModePreset, durationMin: Int): String = "${mode.therapyKeyword} ${durationMin}min"

    private fun note(timestamp: Long, text: String, durationMs: Long) = TE(
        timestamp = timestamp,
        type = TE.Type.NOTE,
        glucoseUnit = GlucoseUnit.MGDL,
        note = text,
        duration = durationMs
    )

    /**
     * The modes that are still running at [now], one per mode (the newest), newest first.
     *
     * A note in AIMI form is read first. Otherwise the note is matched against the mode keywords.
     * When more than one keyword is in the note, the longest one wins, so `work stress 240` is
     * "Work stress" and not "Stress".
     */
    fun activeModes(events: List<TE>, now: Long): List<ActiveModeItem> {
        val found = mutableListOf<ActiveModeItem>()
        for (event in events) {
            if (event.type != TE.Type.NOTE) continue
            val text = event.note ?: continue
            if (event.timestamp + event.duration <= now) continue
            val mode = modeFromAimiCommand(text) ?: modeFromKeyword(text) ?: continue
            found += ActiveModeItem(mode = mode, startedAt = event.timestamp, durationMs = event.duration)
        }
        return found
            .groupBy { it.mode.id }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.startedAt } }
            .filterNot { it.isExpired(now) }
            .sortedByDescending { it.startedAt }
    }

    private fun modeFromAimiCommand(text: String): ModePreset? {
        val match = aimiCommand.find(text) ?: return null
        val name = match.groupValues[2]
        val keyword = when (name.uppercase()) {
            "SPORT"  -> "sport"
            "MEAL"   -> "lunch"
            "STRESS" -> "stress"
            "CANCEL" -> "stop"
            else     -> name.lowercase()
        }
        return ModePresets.findByKeyword(keyword)
    }

    private fun modeFromKeyword(text: String): ModePreset? =
        ModePresets.ALL_MODES
            .filter { text.contains(it.therapyKeyword, ignoreCase = true) }
            .maxByOrNull { it.therapyKeyword.length }
}

/** A mode that is running, as shown on the Active tab. */
data class ActiveModeItem(
    val mode: ModePreset,
    val startedAt: Long,
    val durationMs: Long
) {

    fun remainingMs(now: Long): Long = startedAt + durationMs - now

    fun remainingMinutes(now: Long): Int = (remainingMs(now) / 60_000L).toInt().coerceAtLeast(0)

    fun isExpired(now: Long): Boolean = remainingMs(now) <= 0

    /** How much of the duration is over, from 0 to 100. */
    fun progressPercent(now: Long): Int {
        if (durationMs == 0L) return 100
        return ((now - startedAt).toFloat() / durationMs * 100).toInt().coerceIn(0, 100)
    }
}
