@file:Suppress("ktlint:standard:function-naming")

package app.aaps.ui.compose.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.icons.IcAutomation
import app.aaps.core.ui.compose.icons.Pump
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * Fixed five-item navigation of the Glass and Essential homes.
 *
 * Five is the most a phone tab bar should hold (Apple and Material agree), so the labels never have
 * to shrink to an unreadable size. Profiles is not here: it is the first entry of the Manage sheet.
 */
enum class GlassNavigationTab {
    TREATMENTS, BOLUS, PUMP, SCENARIOS, MANAGEMENT;

    companion object {

        val fixedOrder: List<GlassNavigationTab> = listOf(TREATMENTS, BOLUS, PUMP, SCENARIOS, MANAGEMENT)
    }
}

/**
 * Tabs that need a master or a paired client. Same rule as the Treatments and Scenes buttons of
 * [MainNavigationBar]: a client that is not paired cannot deliver a dose, so the bolus calculator
 * must not be reachable either.
 */
internal val MASTER_ONLY_TABS: Set<GlassNavigationTab> =
    setOf(GlassNavigationTab.TREATMENTS, GlassNavigationTab.SCENARIOS, GlassNavigationTab.BOLUS)

private val NAV_ICON_SIZE = 22.dp
private val BAR_HEIGHT = 64.dp
private val BAR_SHAPE = RoundedCornerShape(32.dp)

/**
 * The bottom bar of the Glass and Essential homes: a rounded bar that floats above the content, in
 * the Glass colours, like the floating tab bar of iOS.
 *
 * Every item is an action (it opens a sheet or a screen), not a tab that stays selected, so no item
 * is ever drawn as selected.
 *
 * The loop accept button is not one of the items. It is a separate button just above the bar,
 * shown only while an open-loop suggestion waits: it must be seen, and it must not push the bar to
 * six items.
 */
@Composable
fun GlassNavigationBar(
    masterOrPairedClient: Boolean,
    onTreatmentClick: () -> Unit,
    onScenariosClick: () -> Unit,
    onManagementClick: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
    /** True only while an open-loop suggestion waits for the user to accept it. Same signal as the
     *  loop button of [MainNavigationBar]. Glass has no other way to this action, so it must stay here. */
    loopActionAvailable: Boolean = false,
    onLoopActionClick: () -> Unit = {},
) {
    val isDark = isGlassDarkMode()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loopActionAvailable) LoopAcceptButton(isDark = isDark, onClick = onLoopActionClick)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .shadow(elevation = 8.dp, shape = BAR_SHAPE)
                .clip(BAR_SHAPE)
                .background(Brush.verticalGradient(listOf(GlassColors.navBarBgStart(isDark), GlassColors.navBarBgEnd(isDark))))
                .border(1.dp, GlassColors.borderCard(isDark), BAR_SHAPE)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassNavigationTab.fixedOrder.forEach { tab ->
                GlassNavItem(
                    label = stringResource(tab.label()),
                    icon = tab.icon(),
                    enabled = masterOrPairedClient || tab !in MASTER_ONLY_TABS,
                    isDark = isDark,
                    onClick = {
                        when (tab) {
                            GlassNavigationTab.TREATMENTS -> onTreatmentClick()
                            GlassNavigationTab.SCENARIOS  -> onScenariosClick()
                            GlassNavigationTab.MANAGEMENT -> onManagementClick()
                            GlassNavigationTab.BOLUS      -> onNavigate(NavigationRequest.Element(ElementType.BOLUS_WIZARD))
                            // The screen of the active pump plugin (same as the Pump entry of the Manage sheet).
                            GlassNavigationTab.PUMP       -> onNavigate(NavigationRequest.Element(ElementType.PUMP))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One item of the bar: icon over a one-line label. At least 48 dp high, the whole cell is the target. */
@Composable
private fun GlassNavItem(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The label below is the accessible name; the icon adds nothing to it.
        Icon(imageVector = icon, contentDescription = null, tint = GlassColors.textBright(isDark), modifier = Modifier.size(NAV_ICON_SIZE))
        Spacer(modifier = Modifier.height(4.dp))
        NavLabel(text = label, color = GlassColors.textMuted(isDark))
    }
}

/** The open-loop suggestion waiting to be accepted: a pill above the bar, in the Glass accent colour. */
@Composable
private fun LoopAcceptButton(isDark: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .shadow(elevation = 6.dp, shape = shape)
            .clip(shape)
            .background(GlassColors.navBarBgStart(isDark))
            .border(1.dp, GlassColors.emerald, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.PlayCircle,
            contentDescription = null,
            tint = GlassColors.emerald,
            modifier = Modifier.size(NAV_ICON_SIZE),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(UiStrings.loop_accept_nav_label),
            style = MaterialTheme.typography.labelLarge,
            color = GlassColors.textBright(isDark),
        )
    }
}

/**
 * One line, never two. With five items each has about 68 pt on a 375 pt wide iPhone, so a long
 * label ("Traitements") fits at the theme's `labelSmall`; it may still shrink to 10 sp, and is cut
 * only when even that does not fit.
 */
@Composable
private fun NavLabel(text: String, color: Color) {
    val style = MaterialTheme.typography.labelSmall
    BasicText(
        text = text,
        style = style.copy(color = color, textAlign = TextAlign.Center),
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun GlassNavigationTab.icon(): ImageVector =
    when (this) {
        GlassNavigationTab.TREATMENTS -> Icons.Default.Fastfood
        GlassNavigationTab.BOLUS      -> Icons.Default.Medication
        GlassNavigationTab.PUMP       -> Pump
        GlassNavigationTab.SCENARIOS  -> IcAutomation
        GlassNavigationTab.MANAGEMENT -> Icons.Default.ManageAccounts
    }

private fun GlassNavigationTab.label(): TextRef =
    when (this) {
        GlassNavigationTab.TREATMENTS -> CoreUiStrings.treatments
        GlassNavigationTab.BOLUS      -> UiStrings.dashboard_v2_nav_bolus
        GlassNavigationTab.PUMP       -> CoreUiStrings.pump
        GlassNavigationTab.SCENARIOS  -> CoreUiStrings.scenes
        GlassNavigationTab.MANAGEMENT -> CoreUiStrings.manage
    }
