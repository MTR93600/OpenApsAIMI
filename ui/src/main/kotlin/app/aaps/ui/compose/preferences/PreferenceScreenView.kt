package app.aaps.ui.compose.preferences

import androidx.compose.runtime.Composable
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenHost

/**
 * Screen that displays a preference screen definition.
 * Used when navigating from search results to a specific screen.
 *
 * Delegates to [PreferenceSubScreenHost] so drill-down nested sub-screens, snackbars, and
 * expansion state match plugin preferences and the main All Preferences flow.
 *
 * @param screenDef The preference screen definition to display
 * @param highlightKey Optional preference key to highlight when navigating from search
 * @param onBackClick Callback when back button is clicked
 */
@Composable
fun PreferenceScreenView(
    screenDef: PreferenceSubScreenDef,
    highlightKey: String? = null,
    onBackClick: () -> Unit
) {
    PreferenceSubScreenHost(
        screenDef = screenDef,
        highlightKey = highlightKey,
        onBackClick = onBackClick
    )
}
