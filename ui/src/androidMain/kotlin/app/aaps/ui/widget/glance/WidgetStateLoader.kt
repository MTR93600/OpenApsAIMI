package app.aaps.ui.widget.glance

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.IntComposedKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.ui.widget.state.WidgetStateBuilder
import dev.zacsweers.metro.Inject

/**
 * The state of one Android widget. What it shows comes from the shared [WidgetStateBuilder], the same
 * rule the iOS Live Activity uses. This class adds only what belongs to one widget on this platform:
 * its background, and the Android colours and drawables (see [toRenderState]).
 */
@Inject
class WidgetStateLoader(
    private val widgetStateBuilder: WidgetStateBuilder,
    private val config: Config,
    private val preferences: Preferences
) {

    suspend fun loadState(appWidgetId: Int): WidgetRenderState {
        val alpha = preferences.get(IntComposedKey.WidgetOpacity, appWidgetId)
        val useBlack = preferences.get(BooleanComposedKey.WidgetUseBlack, appWidgetId)
        val backgroundColor = resolveWidgetBackground(config, useBlack, alpha)
        return widgetStateBuilder.build().toRenderState(backgroundColor)
    }
}
