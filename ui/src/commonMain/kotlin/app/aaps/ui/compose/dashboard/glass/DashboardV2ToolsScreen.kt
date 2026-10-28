package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * The Tools grid of the Glass home: one tile per [DashboardV2ToolAction], the general tiles first, then
 * the AIMI ones under their own title. A tile whose plugin is not in this build is shown disabled.
 *
 * Three columns when the screen is taller than wide, otherwise as many 108 dp columns as fit (DEV read the
 * Android orientation; the shape of the space is the same rule and works on every platform).
 *
 * @param visibleActions null shows every tile; a set hides every tile not in it (the Personalize choice)
 */
@Composable
internal fun DashboardV2ToolsScreen(
    availablePluginClassNames: Set<String>,
    onAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
    visibleActions: Set<DashboardV2ToolAction>? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = if (maxHeight >= maxWidth) GridCells.Fixed(3) else GridCells.Adaptive(minSize = 108.dp)
        LazyVerticalGrid(
            columns = columns,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(UiStrings.dashboard_v2_tools_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val visibleGeneral = DashboardV2ToolAction.general.filter { visibleActions == null || it in visibleActions }
            items(visibleGeneral, key = DashboardV2ToolAction::name) { action ->
                DashboardV2ToolTile(
                    action = action,
                    enabled = action.isAvailable(availablePluginClassNames),
                    onClick = { onAction(action) },
                )
            }
            val visibleAimi = DashboardV2ToolAction.aimi.filter { visibleActions == null || it in visibleActions }
            if (visibleAimi.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(UiStrings.dashboard_v2_tools_aimi_section),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(visibleAimi, key = DashboardV2ToolAction::name) { action ->
                DashboardV2ToolTile(
                    action = action,
                    enabled = action.isAvailable(availablePluginClassNames),
                    onClick = { onAction(action) },
                )
            }
        }
    }
}

@Composable
private fun DashboardV2ToolTile(
    action: DashboardV2ToolAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(action.label)
    val description = if (enabled) {
        label
    } else {
        stringResource(UiStrings.dashboard_v2_tools_tile_unavailable, label, stringResource(UiStrings.dashboard_v2_tools_unavailable))
    }
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minWidth = 48.dp, minHeight = 104.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(
                imageVector = action.icon(),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
