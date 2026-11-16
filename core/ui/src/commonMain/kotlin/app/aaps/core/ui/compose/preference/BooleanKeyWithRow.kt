package app.aaps.core.ui.compose.preference

import androidx.compose.runtime.Composable
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.VisibilityContext

/**
 * Draws one preference row in place of the shared row. Defined here, not in core:keys, because it
 * needs Compose.
 */
fun interface PreferenceRowContent {

    @Composable
    fun Content(visibilityContext: VisibilityContext?)
}

/**
 * A switch key whose row is drawn by its owner ([row]) instead of the shared switch row.
 *
 * [AdaptivePreferenceItem] honours it, so the same row is drawn on every screen that draws the key
 * through the shared renderer: the plugin page, a page opened from the app search, a nested section.
 * Use it when the stored value alone cannot show the state that is in use, for example a switch that
 * was never set and is then decided by another setting.
 *
 * The key itself is unchanged (same key string, title and rules), so search, the sync badge and key
 * lists still see it. The row is responsible for its own visibility (see
 * `calculatePreferenceVisibility`).
 */
class BooleanKeyWithRow(
    private val delegate: BooleanPreferenceKey,
    val row: PreferenceRowContent,
) : BooleanPreferenceKey by delegate

/** This key, drawn by [row] instead of the shared switch row. */
fun BooleanPreferenceKey.withRow(row: PreferenceRowContent): BooleanPreferenceKey = BooleanKeyWithRow(this, row)
