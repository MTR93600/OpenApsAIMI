package app.aaps.plugins.sync.liveactivity

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the user chose to see. Read from the settings at each send. */
data class LiveActivitySettings(
    val enabled: Boolean,
    val showChart: Boolean,
    val showIobCob: Boolean,
    val showLoop: Boolean,
)

/**
 * The text sent to Swift: the widget state (`WidgetStateJson` in `:ui`) plus three flags,
 * `showChart`, `showIobCob` and `showLoop`. The Swift struct `AapsActivityAttributes.ContentState`
 * decodes all of it.
 */
object LiveActivityPayload {

    fun encode(state: JsonObject, settings: LiveActivitySettings): String =
        JsonObject(
            state + mapOf(
                "showChart" to JsonPrimitive(settings.showChart),
                "showIobCob" to JsonPrimitive(settings.showIobCob),
                "showLoop" to JsonPrimitive(settings.showLoop),
            )
        ).toString()
}
