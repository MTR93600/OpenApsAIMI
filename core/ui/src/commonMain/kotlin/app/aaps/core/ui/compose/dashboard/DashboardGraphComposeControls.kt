package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Small pill row: Stats — 6/12/18/24h range — Treatments, all same size, no large full-width buttons. */
@Composable
internal fun DashboardGraphComposeControls(
    state: DashboardGraphControlsUiState,
    strings: DashboardCardStrings,
    modifier: Modifier = Modifier,
) {
    val selected = state.selectedRangeHours
    val onSelect = state.onSelectRange ?: return
    val ranges = listOf(6, 12, 18, 24)
    val commands = LocalDashboardHeroCommands.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AssistChip(
            onClick = commands::openStatsScreen,
            label = { Text(text = strings.statsButton) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ranges.forEach { hours ->
                val label = when (hours) {
                    6 -> strings.range6h
                    12 -> strings.range12h
                    18 -> strings.range18h
                    24 -> strings.range24h
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
            label = { Text(text = strings.treatmentsLabel) },
        )
    }
}
