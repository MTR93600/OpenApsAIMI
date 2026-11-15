package app.aaps.core.interfaces.widget

import kotlinx.serialization.json.JsonObject

/**
 * The shared widget state as JSON, for a widget that is drawn outside Kotlin: the iOS Live Activity
 * and the iOS home and lock screen widgets.
 *
 * The state and its rules live in `:ui` (`WidgetStateBuilder`). A plugin in another module reaches it
 * through this interface, so it does not need a dependency on `:ui`.
 *
 * Safety: the JSON carries the time of the reading and the moment it turns old (`staleAt`). The
 * reader must judge the age again at the time it shows the value, never trust a state built earlier.
 */
interface WidgetStateExport {

    /**
     * Builds the current state.
     *
     * @param withChart also add the readings of the last 3 hours. Leave it off when the chart is not
     * shown, so the state stays small.
     * @param withDetails also add what only the home screen widgets show: target, profile, loop
     * picture, sensitivity, ISF and temp basal. The Live Activity leaves it off.
     */
    suspend fun export(withChart: Boolean, withDetails: Boolean = false): WidgetStateSnapshot
}

/**
 * One built state.
 *
 * @property json the state, in the shape the Swift side decodes (see `WidgetStateJson` in `:ui`).
 * @property bgTimestamp time of the reading in [json] (epoch ms), or null without a reading. A
 * sender uses it to tell a new reading from a repeat of the same one.
 */
data class WidgetStateSnapshot(val json: JsonObject, val bgTimestamp: Long?)
