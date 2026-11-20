package app.aaps.ui.compose.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode

/**
 * True while the Glass or the Essential home is chosen.
 *
 * Read from the preference rather than passed down, because the sheets of the main screen are
 * opened from several places (the bottom bar, the Glass tools, the overview), and each must look
 * the same whichever opened it.
 */
@Composable
fun isGlassChrome(): Boolean {
    val skin by LocalPreferences.current.observe(StringKey.GeneralDashboardSkin).collectAsState()
    return DashboardHomeSelection.resolve(skin).usesGlassChrome
}

/**
 * The container colour of a main-screen bottom sheet: Glass over a Glass or Essential home, and
 * [default] otherwise, so the other homes do not change.
 */
@Composable
fun glassSheetContainerColor(default: Color = MaterialTheme.colorScheme.surface): Color =
    if (isGlassChrome()) GlassColors.sheetBg(isGlassDarkMode()) else default
