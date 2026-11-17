package app.aaps.plugins.sync.tidepool.elements

import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.tidepool.utils.NameUuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * A note the user wrote (bolus, carbs or careportal), uploaded as a Tidepool `reportedState` record.
 *
 * Tidepool accepts `notes` on every record type, but its viewer shows them only on `reportedState`, as a
 * note event on the daily chart. So a note is a record of its own here, not a field of the bolus (#2834).
 *
 * @param source where the note comes from ("bolus", "carbs", "event"); with [timestamp] it makes the origin
 *               id, so a re-upload replaces the note instead of adding it twice
 */
class NoteElement(timestamp: Long, text: String, source: String, dateUtil: DateUtil) :
    BaseElement(timestamp, NameUuid.fromName("AAPS-note-$source$timestamp"), dateUtil) {

    internal var notes: List<String> = listOf(text.trim().take(MAX_NOTE_LENGTH))

    init {
        type = "reportedState"
    }

    override fun putFields(json: JsonObjectBuilder) {
        super.putFields(json)
        json.put("notes", JsonArray(notes.map { JsonPrimitive(it) }))
    }

    companion object {

        // Tidepool refuses a note longer than this
        internal const val MAX_NOTE_LENGTH = 1000
    }
}
