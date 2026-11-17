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

@Composable
fun EssentialMood.colors(): MoodColors {
    val general = AapsTheme.generalColors
    return when (this) {
        EssentialMood.STABLE           -> MoodColors(general.bgInRange, general.bgInRange)
        EssentialMood.RISING           -> MoodColors(general.statusWarning, general.statusWarning)
        EssentialMood.FALLING          -> lerp(general.statusWarning, general.bgLow, 0.4f).let { MoodColors(it, it) }
        EssentialMood.HYPER            -> MoodColors(general.bgHigh, general.bgVeryHigh)
        EssentialMood.HYPO             -> MoodColors(general.bgLow, general.bgVeryLow)
        EssentialMood.TRAJECTORY_HYPER -> MoodColors(general.bgInRange, general.statusWarning)
        EssentialMood.TRAJECTORY_HYPO  -> MoodColors(general.bgInRange, general.bgLow)
        EssentialMood.NO_DATA          -> MaterialTheme.colorScheme.outline.let { MoodColors(it, it) }
    }
}
