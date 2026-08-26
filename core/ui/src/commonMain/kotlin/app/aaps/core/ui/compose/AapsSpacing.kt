package app.aaps.core.ui.compose

import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing.extraLarge
import app.aaps.core.ui.compose.AapsSpacing.extraSmall
import app.aaps.core.ui.compose.AapsSpacing.large
import app.aaps.core.ui.compose.AapsSpacing.medium
import app.aaps.core.ui.compose.AapsSpacing.small
import app.aaps.core.ui.compose.AapsSpacing.xxLarge

/**
 * Centralized spacing and dimension constants for AndroidAPS Compose UI.
 *
 * **Generic spacing scale** — use for padding, margins, arrangement spacing:
 * - [extraSmall] (2.dp), [small] (4.dp), [medium] (8.dp),
 *   [large] (12.dp), [extraLarge] (16.dp), [xxLarge] (24.dp)
 *
 * **Domain-specific dimensions** — named constants for specific UI elements.
 *
 * This is a plain object (no CompositionLocal) because spacing values are
 * static and theme-independent.
 *
 * **Usage:**
 * ```kotlin
 * Modifier.padding(AapsSpacing.medium)  // 8.dp
 * Modifier.size(AapsSpacing.bgCircleSize)  // 126.dp
 * ```
 */
object AapsSpacing {

    // Generic spacing scale
    val extraSmall = 2.dp
    val small = 4.dp
    val medium = 8.dp
    val large = 12.dp
    val extraLarge = 16.dp
    val xxLarge = 24.dp

    // BG circle dimensions
    val bgCircleSize = 126.dp
    val bgRingStrokeWidth = 8.dp

    // Chip dimensions
    val chipCornerRadius = 8.dp
    val chipHeight = 35.dp
    val chipIconSize = 24.dp
    val chipProgressHeight = 3.dp

    /** Widest column a form or status screen should occupy on a tablet. */
    val readableContentMaxWidth = 560.dp

    /** Shortest height of a tappable list row (sensor scan result, etc.). */
    val listRowMinHeight = 56.dp

    /** Below this width a screen keeps one column instead of splitting into two panes. */
    val paneMinWidth = 360.dp

    /** Smallest usable CGM warm-up ring. */
    val warmupRingMin = 120.dp

    /** Largest CGM warm-up ring, so it does not swallow a tablet pane. */
    val warmupRingMax = 240.dp
}
