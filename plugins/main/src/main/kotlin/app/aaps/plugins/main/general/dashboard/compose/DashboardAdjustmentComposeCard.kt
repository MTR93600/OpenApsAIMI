package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.htmlToAnnotatedString
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState

@Composable
internal fun DashboardAdjustmentComposeCard(
    composeState: DashboardEmbeddedComposeState,
    modifier: Modifier = Modifier,
) {
    val state = composeState.adjustmentCardState ?: return
    val cardInnerPaddingH = dimensionResource(R.dimen.dashboard_card_inner_padding_horizontal)
    val cardInnerPaddingV = dimensionResource(R.dimen.dashboard_card_inner_padding_vertical)
    val sectionSpacing = dimensionResource(R.dimen.dashboard_section_spacing)
    val chipPaddingV = dimensionResource(R.dimen.dashboard_chip_padding_vertical)
    val chipPaddingH = dimensionResource(R.dimen.dashboard_chip_padding_horizontal)
    val chipSpacing = dimensionResource(R.dimen.dashboard_chip_spacing)
    val dash = stringResource(CoreUiR.string.value_unavailable_short)
    val resV = state.pumpReservoirPlain.ifBlank { dash }
    val siteV = state.pumpSitePlain.ifBlank { dash }
    val sensV = state.pumpSensorPlain.ifBlank { dash }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { composeState.onOpenAdjustmentDetails?.invoke() }
            .semantics {
                contentDescription = buildString {
                    append(state.glycemiaLine)
                    append(". ")
                    append(state.decisionLine)
                }
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = cardInnerPaddingH, vertical = cardInnerPaddingV),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing),
        ) {
            Text(
                text = stringResource(R.string.dashboard_adjustments),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(text = state.glycemiaLine, style = MaterialTheme.typography.bodyMedium)
            Text(text = state.predictionLine, style = MaterialTheme.typography.bodySmall)
            Text(text = state.iobActivityLine, style = MaterialTheme.typography.bodySmall)
            Text(text = state.decisionLine, style = MaterialTheme.typography.bodySmall)
            state.modeLine?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            val scroll = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AdjustmentPumpBadge(
                    text = stringResource(R.string.dashboard_pump_pill_reservoir, resV),
                    contentDescription = stringResource(R.string.dashboard_pump_pill_reservoir_a11y, resV),
                )
                AdjustmentPumpBadge(
                    text = stringResource(R.string.dashboard_pump_pill_site, siteV),
                    contentDescription = stringResource(R.string.dashboard_pump_pill_site_a11y, siteV),
                )
                AdjustmentPumpBadge(
                    text = stringResource(R.string.dashboard_pump_pill_sensor, sensV),
                    contentDescription = stringResource(R.string.dashboard_pump_pill_sensor_a11y, sensV),
                )
            }
            Text(text = state.safetyLine.htmlToAnnotatedString(), style = MaterialTheme.typography.bodySmall)

            if (state.adjustments.isEmpty()) {
                Text(
                    text = stringResource(R.string.dashboard_no_adjustments),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                state.adjustments.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = chipPaddingH, vertical = chipPaddingV)
                            .padding(bottom = chipSpacing),
                    )
                }
            }

            Button(
                onClick = { composeState.onRunLoopRequested?.invoke() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dashboard_run_loop))
            }
        }
    }
}

@Composable
private fun AdjustmentPumpBadge(
    text: String,
    contentDescription: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}
