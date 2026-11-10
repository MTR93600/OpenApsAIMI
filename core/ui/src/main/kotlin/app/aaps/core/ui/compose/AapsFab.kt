package app.aaps.core.ui.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * AndroidAPS FAB component with proper elevation visibility in dark mode.
 *
 * Uses explicit shadow elevation that is visible in both light and dark themes.
 *
 * @param onClick Called when the FAB is clicked
 * @param modifier Modifier to be applied to the FAB
 * @param content The content of the FAB (typically an Icon)
 *
 * @see AapsFabPreview
 */
@Composable
fun AapsFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val style = glassFabStyle()
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.border(1.dp, style.edge, CircleShape),
        shape = CircleShape,
        containerColor = style.container,
        contentColor = style.content,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 6.dp,
            pressedElevation = 12.dp,
            focusedElevation = 8.dp,
            hoveredElevation = 8.dp
        ),
        content = content
    )
}

/**
 * AndroidAPS small FAB component with proper elevation visibility in dark mode.
 *
 * Uses explicit shadow elevation that is visible in both light and dark themes.
 *
 * @param onClick Called when the FAB is clicked
 * @param modifier Modifier to be applied to the FAB
 * @param content The content of the FAB (typically an Icon)
 *
 * @see AapsSmallFabPreview
 */
@Composable
fun AapsSmallFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val style = glassFabStyle()
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier.border(1.dp, style.edge, CircleShape),
        shape = CircleShape,
        containerColor = style.container,
        contentColor = style.content,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 6.dp,
            pressedElevation = 12.dp,
            focusedElevation = 8.dp,
            hoveredElevation = 8.dp
        ),
        content = content
    )
}

/** Colours of the round floating buttons: the same dark (or light) glass look as the cards and the bottom bar. */
internal class GlassFabStyle(val container: Color, val content: Color, val edge: Brush)

@Composable
internal fun glassFabStyle(): GlassFabStyle {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (isDark) {
        GlassFabStyle(
            container = Color(0xFF16233A),
            content = Color(0xFF7DD3FC),
            edge = Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x1AFFFFFF)))
        )
    } else {
        GlassFabStyle(
            container = Color(0xFFEAF2FF),
            content = Color(0xFF0369A1),
            edge = Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x66CBD5E1)))
        )
    }
}
