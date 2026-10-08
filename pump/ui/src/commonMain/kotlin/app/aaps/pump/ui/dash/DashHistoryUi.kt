package app.aaps.pump.ui.dash

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsSpacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * UI model for one Dash pod history entry.
 *
 * All display strings are pre-resolved by the platform: the Android
 * `DashPodHistoryScreen` maps `HistoryRecord` to this model using
 * `ResourceHelper`, `ProfileUtil` and `DateUtil`, so this commonMain screen
 * stays free of Android-only dependencies.
 */
data class DashHistoryEntry(
    val id: Long,
    val timestamp: Long,
    val commandName: String,
    val time: String,
    val dayHeader: String,
    val isSuccess: Boolean,
    val description: String = "",
    val extra: String? = null
)

/**
 * UI model for one history filter chip (replaces the Android-only
 * `PumpHistoryEntryGroup`).
 */
data class DashHistoryFilter(
    val id: String,
    val label: String
)

/**
 * Dash pod history screen, ported to commonMain.
 *
 * Ported from `pump/omnipod/dash/.../ui/compose/DashPodHistoryScreen.kt`.
 * The Android-only inputs (`List<HistoryRecord>`, `ResourceHelper`,
 * `ProfileUtil`, `LocalDateUtil`) are replaced by pre-resolved [entries]
 * and [filters]; filtering state is hoisted via [selectedFilterId] and
 * [onFilterSelected].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DashPodHistoryScreen(
    entries: List<DashHistoryEntry>,
    filters: List<DashHistoryFilter>,
    selectedFilterId: String,
    onFilterSelected: (String) -> Unit
) {
    val groupedByDay = entries.groupBy { it.dayHeader }

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter chips
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            filters.forEach { filter ->
                FilterChip(
                    selected = selectedFilterId == filter.id,
                    onClick = { onFilterSelected(filter.id) },
                    label = { Text(filter.label) }
                )
            }
        }

        // History list with day headers
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            groupedByDay.forEach { (dayHeader, itemsForDay) ->
                stickyHeader(key = "header_$dayHeader") {
                    Text(
                        text = dayHeader,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(vertical = AapsSpacing.medium),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                items(itemsForDay, key = { it.id }) { entry ->
                    DashHistoryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun DashHistoryCard(entry: DashHistoryEntry) {
    AapsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(AapsSpacing.large),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (entry.isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp),
                tint = if (entry.isSuccess) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )

            Spacer(Modifier.width(AapsSpacing.large))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.commandName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.isSuccess) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = entry.time,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (entry.description.isNotEmpty()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AapsSpacing.small)
                    )
                }

                entry.extra?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AapsSpacing.extraSmall)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun DashHistoryPreview() {
    DashPodHistoryScreen(
        entries = listOf(
            DashHistoryEntry(
                id = 1,
                timestamp = 0,
                commandName = "Set bolus",
                time = "12:30",
                dayHeader = "Today",
                isSuccess = true,
                description = "2.5 U",
                extra = "Total delivered: 2.5 U"
            ),
            DashHistoryEntry(
                id = 2,
                timestamp = 0,
                commandName = "Set temporary basal",
                time = "11:15",
                dayHeader = "Today",
                isSuccess = false,
                description = "Failed to send"
            )
        ),
        filters = listOf(
            DashHistoryFilter(id = "all", label = "All"),
            DashHistoryFilter(id = "bolus", label = "Bolus"),
            DashHistoryFilter(id = "basal", label = "Basal")
        ),
        selectedFilterId = "all",
        onFilterSelected = {}
    )
}
