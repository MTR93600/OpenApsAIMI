package app.aaps.core.ui.compose.dashboard

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.htmlToAnnotatedString

@Composable
internal fun DashboardAdjustmentComposeCard(
    state: DashboardAdjustmentCardUiState?,
    strings: DashboardCardStrings,
    modifier: Modifier = Modifier,
) {
    if (state == null) return
    val cardInnerPaddingH = 16.dp
    val cardInnerPaddingV = 12.dp
    val sectionSpacing = 8.dp
    val chipPaddingV = 4.dp
    val chipPaddingH = 8.dp
    val chipSpacing = 4.dp
    val dash = strings.unavailableShort
    val resV = state.pumpReservoirPlain.ifBlank { dash }
    val siteV = state.pumpSitePlain.ifBlank { dash }
    val sensV = state.pumpSensorPlain.ifBlank { dash }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { state.onOpenAdjustmentDetails?.invoke() }
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
                text = strings.adjustmentsTitle,
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
                    text = strings.pumpReservoir.format(resV),
                    contentDescription = strings.pumpReservoirA11y.format(resV),
                )
                AdjustmentPumpBadge(
                    text = strings.pumpSite.format(siteV),
                    contentDescription = strings.pumpSiteA11y.format(siteV),
                )
                AdjustmentPumpBadge(
                    text = strings.pumpSensor.format(sensV),
                    contentDescription = strings.pumpSensorA11y.format(sensV),
                )
            }
            Text(text = state.safetyLine.htmlToAnnotatedString(), style = MaterialTheme.typography.bodySmall)

            if (state.adjustments.isEmpty()) {
                Text(
                    text = strings.noAdjustments,
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
                onClick = { state.onRunLoopRequested?.invoke() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(strings.runLoop)
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
