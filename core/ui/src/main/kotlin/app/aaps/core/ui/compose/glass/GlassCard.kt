package app.aaps.core.ui.compose.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Bare Glass-styled card — no title, no header, no collapse behavior (unlike [GlassSectionCard]).
 * A drop-in visual replacement for [app.aaps.core.ui.compose.AapsCard] in Glass-restyled screens,
 * matching its `selected` highlight behavior. Sets [LocalContentColor] for its subtree, same
 * mechanism as [GlassSectionCard].
 */
@Composable
fun GlassCard(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) GlassColors.skyBlue.copy(alpha = 0.6f) else GlassColors.borderCard(isDark)
    val backgroundBrush = if (selected) {
        Brush.verticalGradient(listOf(GlassColors.skyBlue.copy(alpha = 0.18f), GlassColors.skyBlue.copy(alpha = 0.08f)))
    } else {
        Brush.verticalGradient(listOf(GlassColors.cardBgStart(isDark), GlassColors.cardBgEnd(isDark)))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundBrush)
            .border(1.dp, borderColor, shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides GlassColors.textBright(isDark)) {
            content()
        }
    }
}
