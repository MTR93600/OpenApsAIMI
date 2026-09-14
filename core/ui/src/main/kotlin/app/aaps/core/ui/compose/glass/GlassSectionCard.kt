package app.aaps.core.ui.compose.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Glass-styled replacement for the `AapsCard { header row + AnimatedVisibility(content) }` pattern
 * already repeated across `StatsScreen.kt`. Sets [LocalContentColor] for its whole subtree, so plain
 * `Text()` calls inside [content] that don't set an explicit color automatically render in the
 * correct Glass text color for the given [isDark] mode — no need to touch every child composable
 * individually.
 *
 * @param onToggleExpanded null means the card is not collapsible (always shows [content]); non-null
 *   makes the whole header row clickable and shows the expand/collapse chevron.
 */
@Composable
fun GlassSectionCard(
    title: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(GlassColors.cardBgStart(isDark), GlassColors.cardBgEnd(isDark))))
            .border(1.dp, GlassColors.borderCard(isDark), shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides GlassColors.textBright(isDark)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggleExpanded != null) Modifier.clickable { onToggleExpanded() } else Modifier)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = GlassColors.textBright(isDark),
                    modifier = Modifier.weight(1f)
                )
                trailingAction?.invoke()
                if (onToggleExpanded != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = GlassColors.textMuted(isDark)
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    content = content
                )
            }
        }
    }
}
