package app.aaps.core.ui.compose.pump

import androidx.compose.runtime.Composable

/**
 * Keeps the screen on while this composable is in the composition, and gives back the previous state
 * when it leaves.
 *
 * Use it in pump wizards: a screen that goes dark in the middle of filling, priming or activating a
 * patch hides the next instruction, and on some phones also slows the Bluetooth link.
 */
@Composable
expect fun KeepScreenOnEffect()
