package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.TonalIcon
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon

/**
 * Pre-formatted strings for the dashboard quick-actions bar.
 * Replaces `stringResource(R.string.*)` — the platform resolves resources.
 */
data class QuickActionsStrings(
    val advisorLabel: String,
    val advisorContentDescription: String,
    val advisorAnnounced: String,
    val adjustLabel: String,
    val adjustContentDescription: String,
    val adjustAnnounced: String,
    val mealLabel: String,
    val mealContentDescription: String,
    val mealAnnounced: String,
    val contextLabel: String,
    val contextContentDescription: String,
    val contextAnnounced: String,
)

/**
 * AIMI quick-actions bar for the dashboard: four entries ([TonalIcon] + label),
 * aligned with the "Manage / Traitements" Compose sheets.
 *
 * Ported from `plugins/main/.../dashboard/compose/DashboardQuickActionsBar` to
 * commonMain:
 * - `LocalView.performHapticFeedback(VIRTUAL_KEY)` → [DashboardHaptics] seam
 * - `AccessibilityManager` announcement → optional [onAnnounce] callback
 *   (platform wires TalkBack/VoiceOver; null = silent)
 * - `stringResource(R.string.*)` → [QuickActionsStrings]
 */
@Composable
fun DashboardQuickActionsBar(
    onAdvisor: () -> Unit,
    onAdjust: () -> Unit,
    onMeal: () -> Unit,
    onContext: () -> Unit,
    strings: QuickActionsStrings,
    haptics: DashboardHaptics = NoOpDashboardHaptics,
    onAnnounce: ((String) -> Unit)? = null,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    fun wrapped(action: () -> Unit, announce: String): () -> Unit = {
        haptics.vibrate()
        action()
        onAnnounce?.invoke(announce)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 4.dp,
                vertical = if (compact) 2.dp else 8.dp,
            ),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        QuickActionTile(
            elementType = ElementType.PROFILE_HELPER,
            label = strings.advisorLabel,
            contentDescription = strings.advisorContentDescription,
            onClick = wrapped(onAdvisor, strings.advisorAnnounced),
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            elementType = ElementType.SETTINGS,
            label = strings.adjustLabel,
            contentDescription = strings.adjustContentDescription,
            onClick = wrapped(onAdjust, strings.adjustAnnounced),
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            elementType = ElementType.QUICK_WIZARD_MANAGEMENT,
            label = strings.mealLabel,
            contentDescription = strings.mealContentDescription,
            onClick = wrapped(onMeal, strings.mealAnnounced),
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            elementType = ElementType.STATISTICS,
            label = strings.contextLabel,
            contentDescription = strings.contextContentDescription,
            onClick = wrapped(onContext, strings.contextAnnounced),
            compact = compact,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickActionTile(
    elementType: ElementType,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = elementType.color()
    Column(
        modifier = modifier
            .semantics { this.contentDescription = "$label. $contentDescription" }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalIcon(
            icon = elementType.icon(),
            color = accent,
        )
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = accent,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                top = if (compact) 4.dp else 6.dp,
                start = 2.dp,
                end = 2.dp,
            ),
        )
    }
}
