package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.aaps.core.interfaces.overview.PluginStatusBadge
import app.aaps.core.interfaces.overview.PluginStatusLevel
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme

/**
 * A single plugin's status chip on the Overview screen, next to the BG circle - see
 * [PluginStatusBadgeSource][app.aaps.core.interfaces.overview.PluginStatusBadgeSource] for why
 * `:ui` renders this without depending on whichever plugin supplied it.
 *
 * Hidden entirely while [PluginStatusLevel.IDLE] and there is nothing to count, so a plugin with
 * no news to report adds no chrome.
 */
@Composable
internal fun PluginStatusChip(
    badge: PluginStatusBadge,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (badge.level == PluginStatusLevel.IDLE && badge.badgeCount == 0) return

    val snackbarColors = AapsTheme.snackbarColors
    val (containerColor, contentColor) = when (badge.level) {
        PluginStatusLevel.IDLE -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        PluginStatusLevel.PROCESSING -> snackbarColors.infoContainer to snackbarColors.onInfoContainer
        PluginStatusLevel.READY -> snackbarColors.successContainer to snackbarColors.onSuccessContainer
        PluginStatusLevel.WARNING -> snackbarColors.warningContainer to snackbarColors.onWarningContainer
        PluginStatusLevel.ERROR -> snackbarColors.errorContainer to snackbarColors.onErrorContainer
    }

    Surface(
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = containerColor.copy(alpha = 0.2f),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
        ) {
            StatusIcon(badgeCount = badge.badgeCount, tint = contentColor)
            if (badge.statusMessage.isNotEmpty()) {
                Text(
                    text = badge.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.padding(start = AapsSpacing.medium)
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(badgeCount: Int, tint: Color) {
    if (badgeCount > 0) {
        BadgedBox(badge = { Badge { Text(text = badgeCount.toString()) } }) {
            Icon(
                imageVector = Icons.Default.FactCheck,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(AapsSpacing.chipIconSize)
            )
        }
    } else {
        Icon(
            imageVector = Icons.Default.FactCheck,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(AapsSpacing.chipIconSize)
        )
    }
}
