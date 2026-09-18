package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiRecommendation
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPriority

/**
 * One advisor recommendation, shown as a card with an optional Apply button.
 *
 * Shared by the PK/PD Setup screen and by the AIMI Profile Advisor screen so both look the same.
 * Only an [AimiAction.PreferenceUpdate] can be applied; any other action renders as a plain card.
 */
@Composable
fun AimiRecommendationCard(
    recommendation: AimiRecommendation,
    applyLabel: TextRef = ApsStrings.aimi_pkpd_advisor_apply,
    showPriority: Boolean = false,
    onApply: ((AimiAction.PreferenceUpdate) -> Unit)? = null,
) {
    val update = recommendation.action as? AimiAction.PreferenceUpdate
    val description = recommendationDescription(recommendation)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(AapsSpacing.medium)) {
            if (showPriority) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    Spacer(
                        modifier = Modifier
                            .size(AapsSpacing.medium)
                            .background(priorityColor(recommendation.priority), CircleShape),
                    )
                    Text(stringResource(recommendation.title), style = MaterialTheme.typography.titleSmall)
                }
            } else {
                Text(stringResource(recommendation.title), style = MaterialTheme.typography.titleSmall)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = AapsSpacing.extraSmall))
            if (update != null && onApply != null) {
                Button(
                    onClick = { onApply(update) },
                    modifier = Modifier.padding(top = AapsSpacing.small),
                ) {
                    Text(stringResource(applyLabel))
                }
            }
        }
    }
}

/**
 * Resolves the description text.
 *
 * Some recommendations carry their arguments in `descriptionArgs`, others already carry them inside
 * the [TextRef]. One engine path emits an empty literal description, so the reason of the action is
 * used instead of showing nothing.
 */
@Composable
private fun recommendationDescription(recommendation: AimiRecommendation): String {
    val args = recommendation.descriptionArgs
    val resolved = when (args.size) {
        0    -> stringResource(recommendation.description)
        1    -> stringResource(recommendation.description, args[0])
        2    -> stringResource(recommendation.description, args[0], args[1])
        else -> stringResource(recommendation.description)
    }
    return resolved.ifBlank { recommendation.action?.reason ?: "" }
}

@Composable
private fun priorityColor(priority: AimiPriority): Color = when (priority) {
    AimiPriority.Critical -> AapsTheme.generalColors.statusCritical
    AimiPriority.High     -> AapsTheme.generalColors.statusWarning
    AimiPriority.Medium   -> MaterialTheme.colorScheme.primary
    AimiPriority.Low      -> AapsTheme.generalColors.statusNormal
}
