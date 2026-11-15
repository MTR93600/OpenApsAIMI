package app.aaps.plugins.sync.liveactivity

/**
 * ActivityKit, as the Kotlin plugin is allowed to see it.
 *
 * Swift owns ActivityKit (`SwiftLiveActivityCompanion` in `ios/app/Shared/LiveActivityBridge.swift`).
 * Kotlin decides when to show what; Swift only moves the JSON into the activity. The JSON is
 * `WidgetStateJson` from `:ui` plus the three `show…` flags of [LiveActivityPayload].
 *
 * Calls come from any thread. Swift moves them to the main queue.
 */
interface LiveActivityCompanion {

    /** Called once when the plugin starts. [callback] stays valid until [stop]. */
    fun start(callback: LiveActivityCallback)

    /**
     * Shows [stateJson]. Updates the running activity, or starts one when the app is in the
     * foreground (iOS allows a start only then). In the background Swift keeps the state and starts
     * the activity when the app becomes active.
     */
    fun update(stateJson: String)

    /** Ends every activity of this app, at once. Used when the user turns the Live Activity off. */
    fun end()

    /** Drops the callback. The activity stays; it turns stale by itself. Safe to call more than once. */
    fun stop()
}

/** What Swift tells the plugin. */
interface LiveActivityCallback {

    /**
     * Swift wants a fresh state now: the app became active, or the user restarted the activity with
     * the App Shortcut. The plugin answers with [LiveActivityCompanion.update], outside the throttle.
     */
    fun refreshRequested()
}

/**
 * Set from the Swift app entry, before the graph starts the plugin. The same shape as
 * `ConnectIqRegistry`: Swift builds it, Kotlin cannot.
 */
object LiveActivityRegistry {
    var companion: LiveActivityCompanion? = null
}

fun installLiveActivityCompanion(companion: LiveActivityCompanion) {
    LiveActivityRegistry.companion = companion
}
