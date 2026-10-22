package app.aaps.ui.compose.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.ui.compose.overview.BgInfoSection
import app.aaps.ui.compose.overview.SensitivityChipBlock
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.IobCobChipsRow
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.chips.SensitivityUiState

/**
 * Dashboard V2's compact summary built from the exact Overview presentation state.
 *
 * This deliberately reuses the Overview glucose circle and IOB/COB/sensitivity controls rather
 * than reconstructing glucose colors, freshness, units, or insulin values in the dashboard layer.
 * The caller supplies states already produced by the Overview view models.
 */
@Composable
internal fun DashboardV2OverviewSummary(
    bgInfo: BgInfoData?,
    timeAgoText: String,
    iob: IobUiState,
    cob: CobUiState,
    sensitivity: SensitivityUiState,
    onIobClick: () -> Unit,
    contextActionLabel: String? = null,
    onOpenContext: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BgInfoSection(
            bgInfo = bgInfo,
            timeAgoText = timeAgoText,
            size = 112.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IobCobChipsRow(
                iobUiState = iob,
                cobUiState = cob,
                onIobChipClick = onIobClick,
            )
            SensitivityChipBlock(
                state = sensitivity,
                modifier = Modifier.fillMaxWidth(),
            )
            if (contextActionLabel != null && onOpenContext != null) {
                TextButton(onClick = onOpenContext) {
                    Text(contextActionLabel)
                }
            }
        }
    }
}
