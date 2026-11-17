package app.aaps.ui.compose.dashboard.essential

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import app.aaps.core.ui.compose.AapsTheme

/**
 * The two colours of a mood, both from the theme: [core] fills the shape, [accent] is the colour it
 * leans toward (a trajectory shows as a gradient from in-range to where glucose is going).
 */
data class MoodColors(val core: Color, val accent: Color)

/** How much of its own grey a theme colour takes for the shape. The glucose colours are made for thin lines and are too loud as a large fill. */
private const val SOFTEN_GREY = 0.30f

/** How much darker the shape is than the softened colour, so white digits keep their contrast. */
private const val SOFTEN_DARK = 0.12f

/**
 * A calmer version of a theme colour: mixed with a grey of the same brightness (less saturation, same
 * light), then a little darker. Derived from the theme, so a theme change still changes the shape.
 */
internal fun Color.soften(): Color {
    val grey = 0.299f * red + 0.587f * green + 0.114f * blue
    return lerp(lerp(this, Color(grey, grey, grey, alpha), SOFTEN_GREY), Color.Black.copy(alpha = alpha), SOFTEN_DARK)
}

@Composable
fun EssentialMood.colors(): MoodColors {
    val general = AapsTheme.generalColors
    val raw = when (this) {
        EssentialMood.STABLE           -> MoodColors(general.bgInRange, general.bgInRange)
        EssentialMood.RISING           -> MoodColors(general.statusWarning, general.statusWarning)
        EssentialMood.FALLING          -> lerp(general.statusWarning, general.bgLow, 0.4f).let { MoodColors(it, it) }
        EssentialMood.HYPER            -> MoodColors(general.bgHigh, general.bgVeryHigh)
        EssentialMood.HYPO             -> MoodColors(general.bgLow, general.bgVeryLow)
        EssentialMood.TRAJECTORY_HYPER -> MoodColors(general.bgInRange, general.statusWarning)
        EssentialMood.TRAJECTORY_HYPO  -> MoodColors(general.bgInRange, general.bgLow)
        EssentialMood.NO_DATA          -> MaterialTheme.colorScheme.outline.let { MoodColors(it, it) }
    }
    return MoodColors(raw.core.soften(), raw.accent.soften())
}
