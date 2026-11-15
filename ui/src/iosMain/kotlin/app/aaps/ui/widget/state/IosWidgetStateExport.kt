package app.aaps.ui.widget.state

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.widget.WidgetStateExport
import app.aaps.core.interfaces.widget.WidgetStateSnapshot
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * The widget state for the iOS Live Activity and the iOS widgets. iOS only, so the Android graph does not change:
 * the Android widgets keep using `WidgetStateLoader`.
 */
@ContributesBinding(AppScope::class)
@Inject
class IosWidgetStateExport(
    private val builder: WidgetStateBuilder,
    private val rh: TextResolver
) : WidgetStateExport {

    override suspend fun export(withChart: Boolean, withDetails: Boolean): WidgetStateSnapshot {
        val state = builder.build(withChart = withChart)
        return WidgetStateSnapshot(WidgetStateJson.encode(state, rh, withDetails), state.bgTimestamp)
    }
}
