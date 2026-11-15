package app.aaps.plugins.sync.homewidget

/**
 * The iOS home and lock screen widgets (WidgetKit), as the Kotlin plugin is allowed to see them.
 *
 * Swift owns the App Group file and WidgetKit (`SwiftHomeWidgetCompanion` in
 * `ios/app/Shared/LiveActivityBridge.swift`). Kotlin decides what to save and when; Swift writes the
 * JSON to the App Group container and asks WidgetKit to draw the widgets again. The widget extension
 * reads the file itself, because it is a separate process that cannot load the Kotlin framework.
 *
 * The JSON is `WidgetStateJson` from `:ui`, built with the chart and the details, plus the three show
 * flags of `LiveActivityPayload` (all true), because Swift decodes it as
 * `AapsActivityAttributes.ContentState`, which needs them.
 *
 * Calls come from any thread.
 */
interface HomeWidgetCompanion {

    /**
     * Saves [stateJson] for the widgets. With [reload] true it also asks WidgetKit to draw them again;
     * with false the widgets pick the file up at their next reload. Without an App Group container
     * (the owner has not added it in Xcode yet) Swift logs it and does nothing.
     */
    fun save(stateJson: String, reload: Boolean)
}

/**
 * Set from the Swift app entry, before the graph starts the plugin. The same shape as
 * `LiveActivityRegistry`: Swift builds it, Kotlin cannot.
 */
object HomeWidgetRegistry {
    var companion: HomeWidgetCompanion? = null
}

fun installHomeWidgetCompanion(companion: HomeWidgetCompanion) {
    HomeWidgetRegistry.companion = companion
}
