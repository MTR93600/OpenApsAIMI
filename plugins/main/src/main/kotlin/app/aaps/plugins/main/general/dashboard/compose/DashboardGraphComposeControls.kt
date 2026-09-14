package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState

/** Small pill row: Stats — 6/12/18/24h range — Treatments, all same size, no large full-width buttons. */
@Composable
internal fun DashboardGraphComposeControls(
    composeState: DashboardEmbeddedComposeState,
    modifier: Modifier = Modifier,
) {
    val selected = composeState.graphUiState.rangeHours
    val onSelect = composeState.graphCommands.onSelectRange ?: return
    val ranges = listOf(6, 12, 18, 24)
    val commands = LocalDashboardHeroCommands.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AssistChip(
            onClick = commands::openStatsScreen,
            label = { Text(text = stringResource(R.string.stats_button)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ranges.forEach { hours ->
                val label = when (hours) {
                    6 -> stringResource(R.string.graph_long_scale_6h)
                    12 -> stringResource(R.string.graph_long_scale_12h)
                    18 -> stringResource(R.string.graph_long_scale_18h)
                    24 -> stringResource(R.string.graph_long_scale_24h)
                    else -> "${hours}h"
                }
                FilterChip(
                    selected = hours == selected,
                    onClick = { onSelect(hours) },
                    label = { Text(text = label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        AssistChip(
            onClick = commands::openTreatmentsScreen,
            label = { Text(text = stringResource(CoreUiR.string.overview_treatment_label)) },
        )
    }
}
