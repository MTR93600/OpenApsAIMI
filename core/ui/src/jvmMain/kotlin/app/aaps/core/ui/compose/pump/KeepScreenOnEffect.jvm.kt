package app.aaps.core.ui.compose.pump

import androidx.compose.runtime.Composable

/**
 * Desktop: nothing to do. The desktop app has no pump wizard that talks to a patch, and the screen saver
 * of a computer is not the app's to switch off.
 */
@Composable
actual fun KeepScreenOnEffect() = Unit
