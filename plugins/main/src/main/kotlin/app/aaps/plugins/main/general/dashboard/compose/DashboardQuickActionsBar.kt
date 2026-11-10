package app.aaps.plugins.main.general.dashboard.compose

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.TonalIcon
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.plugins.main.R

/**
 * Barre d’actions AIMI du dashboard : même quatre entrées que l’ancienne rangée de chips,
 * présentation alignée sur les feuilles Compose « Manage / Traitements » ([TonalIcon] + libellé).
 */
@Composable
fun DashboardQuickActionsBar(
    onAdvisor: () -> Unit,
    onAdjust: () -> Unit,
    onMeal: () -> Unit,
    onContext: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    fun announceIfAccessibilityEnabled(@StringRes messageRes: Int) {
        val manager = accessibilityManager
        if (manager != null && manager.isEnabled && manager.isTouchExplorationEnabled) {
            view.announceForAccessibility(context.getString(messageRes))
        }
    }
    fun wrapped(action: () -> Unit, @StringRes announceRes: Int): () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
        announceIfAccessibilityEnabled(announceRes)
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
            label = stringResource(R.string.advisor_button),
            contentDescription = stringResource(R.string.dashboard_cd_chip_aimi_advisor),
            onClick = wrapped(onAdvisor, R.string.dashboard_chip_announced_advisor_opened),
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            elementType = ElementType.SETTINGS,
            label = stringResource(R.string.adjust_button),
            contentDescription = stringResource(R.string.dashboard_cd_chip_adjust),
            onClick = wrapped(onAdjust, R.string.dashboard_chip_announced_adjust_opened),
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            elementType = ElementType.QUICK_WIZARD_MANAGEMENT,
            label = stringResource(R.string.dashboard_mode_meal),
            contentDescription = stringResource(R.string.dashboard_cd_chip_meal_mode),
            onClick = wrapped(onMeal, R.string.dashboard_chip_announced_meal_mode_opened),
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        QuickActionTile(
            elementType = ElementType.STATISTICS,
            label = stringResource(R.string.aimi_context),
            contentDescription = stringResource(R.string.dashboard_cd_chip_aimi_context),
            onClick = wrapped(onContext, R.string.dashboard_chip_announced_context_opened),
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
