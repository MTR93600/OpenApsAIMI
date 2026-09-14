package app.aaps.core.ui.compose.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalInspectionMode
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.UiMode
import app.aaps.core.ui.compose.LocalPreferences

/**
 * The single source of "is this screen in dark mode" for every Glass-styled screen: the app's own
 * GeneralDarkMode preference, never the raw OS theme. Mirrors the identical chain already used in
 * `GlassOverviewComposeEmbedded.kt` (`:plugins:main`) and the Loop Dashboard's nav destination
 * (`AppNavGraph.kt`) — this is the third use of the same chain, now shared instead of re-derived.
 */
@Composable
fun isGlassDarkMode(): Boolean {
    if (LocalInspectionMode.current) return isSystemInDarkTheme()
    val preferences = LocalPreferences.current
    val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
    return when (UiMode.fromString(darkModeValue)) {
        UiMode.LIGHT  -> false
        UiMode.DARK   -> true
        UiMode.SYSTEM -> isSystemInDarkTheme()
    }
}

/**
 * The vertical gradient background every Glass screen uses, wrapping the screen's scrollable content.
 */
@Composable
fun GlassScreenBackground(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GlassColors.screenBgTop(isDark), GlassColors.screenBgBottom(isDark), GlassColors.screenBgTop(isDark))
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            CompositionLocalProvider(LocalContentColor provides GlassColors.textBright(isDark)) {
                content()
            }
        }
    }
}
