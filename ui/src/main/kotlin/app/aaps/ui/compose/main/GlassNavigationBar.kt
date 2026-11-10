@file:Suppress("ktlint:standard:function-naming")

package app.aaps.ui.compose.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import app.aaps.core.ui.compose.htmlToAnnotatedString
import app.aaps.core.ui.compose.PumpInfoState
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcAutomation
import app.aaps.core.ui.compose.icons.IcProfile
import app.aaps.core.ui.compose.icons.Pump
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.R
import app.aaps.core.ui.R as CoreUiR

/** Stable six-item navigation used only by the Glass home shell. */
enum class GlassNavigationTab {
    TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT;

    companion object {
        val fixedOrder: List<GlassNavigationTab> = listOf(TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT)
    }
}

// Same rule MainNavigationBar applies to its own Treatments/Scenes buttons (ElementType.BOLUS_WIZARD is
// MASTER_OR_PAIRED_CLIENT-visible; a non-paired client has no channel to actually deliver a dose, so the
// calculator must not be reachable). Hoisted so it isn't rebuilt every recomposition.
internal val MASTER_ONLY_TABS = setOf(GlassNavigationTab.TREATMENTS, GlassNavigationTab.SCENARIOS, GlassNavigationTab.BOLUS)

// Smaller than NavigationBarItem's 24dp default — 6 fixed tabs (7 when the loop-accept item shows) is above
// Material's recommended max of 5, so a slightly smaller icon (paired with labelSmall text below) keeps the
// bar from feeling cramped.
private val NAV_ICON_SIZE = 22.dp
private val NAV_BAR_HEIGHT = 72.dp

@Composable
fun GlassNavigationBar(
    masterOrPairedClient: Boolean,
    onTreatmentClick: () -> Unit,
    onScenariosClick: () -> Unit,
    onManagementClick: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
    /** True only while an open-loop suggestion is waiting for the user to accept it — same signal
     *  MainNavigationBar's own loop-accept button uses. There is no other path to this action in the
     *  Glass Compose UI, so it must stay reachable here even though the other 6 tabs are fixed. */
    loopActionAvailable: Boolean = false,
    onLoopActionClick: () -> Unit = {},
) {
    // Same look on every screen: a dark (or light) glass strip with a thin top edge, all items the same height,
    // labels on one line.
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val muted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = Color(0xFF38BDF8),
        selectedTextColor = Color(0xFF38BDF8),
        indicatorColor = Color.Transparent,
        unselectedIconColor = muted,
        unselectedTextColor = muted,
        disabledIconColor = muted.copy(alpha = 0.38f),
        disabledTextColor = muted.copy(alpha = 0.38f),
    )
    val stripBrush = Brush.verticalGradient(
        if (isDark) listOf(Color(0xFF0E1A2E), Color(0xFF070E1B)) else listOf(Color(0xFFF8FAFC), Color(0xFFE8EEF8))
    )
    val edgeColor = if (isDark) Color(0x33FFFFFF) else Color(0x33000000)

    val pumpInfo by PumpInfoState.html.collectAsState()
    var showPumpInfo by remember { mutableStateOf(false) }
    // A long press on the Pump button shows the pump line; the release must not also open the pump screen.
    var longPressHandled by remember { mutableStateOf(false) }

    NavigationBar(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets(0),
        modifier = modifier
            .background(stripBrush)
            .drawBehind { drawLine(edgeColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2f) }
            .height(NAV_BAR_HEIGHT),
    ) {
        GlassNavigationTab.fixedOrder.forEach { tab ->
            val enabled = masterOrPairedClient || tab !in MASTER_ONLY_TABS
            NavigationBarItem(
                selected = false,
                enabled = enabled,
                onClick = {
                    when (tab) {
                        GlassNavigationTab.TREATMENTS -> onTreatmentClick()
                        GlassNavigationTab.SCENARIOS   -> onScenariosClick()
                        GlassNavigationTab.MANAGEMENT  -> onManagementClick()
                        GlassNavigationTab.PROFILES    -> onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT))
                        GlassNavigationTab.BOLUS        -> onNavigate(NavigationRequest.Element(ElementType.BOLUS_WIZARD))
                        // Opens the active pump plugin's own status screen (same path as the Manage sheet's
                        // Pump entry) — not GlassPumpDetailScreen's reservoir/battery summary.
                        GlassNavigationTab.PUMP         -> {
                            if (longPressHandled) longPressHandled = false
                            else onNavigate(NavigationRequest.Element(ElementType.PUMP))
                        }
                    }
                },
                modifier = if (tab == GlassNavigationTab.PUMP) {
                    // Initial pass: the tab's own click handling must not hide the long press.
                    Modifier.pointerInput(pumpInfo) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            }
                            if (up == null && pumpInfo.isNotBlank()) {
                                longPressHandled = true
                                showPumpInfo = true
                            }
                        }
                    }
                } else Modifier,
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = stringResource(tab.labelRes()),
                        modifier = Modifier.size(NAV_ICON_SIZE),
                    )
                },
                label = {
                    Text(
                        text = stringResource(tab.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                },
                alwaysShowLabel = true,
                colors = colors,
            )
        }
        // Loop accept action (visible only when AAPS has a pending suggestion in open loop) — the fixed 6
        // tabs above cover the mockup's steady-state navigation, but this transient action has no other
        // path in the Glass Compose UI (not the drawer, not the Management sheet), so it must stay
        // reachable as a 7th item when it applies, matching MainNavigationBar's own loop-accept button.
        if (loopActionAvailable) {
            NavigationBarItem(
                selected = false,
                onClick = onLoopActionClick,
                icon = {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = AapsTheme.generalColors.statusNormal, contentColor = Color.Black) {
                                Text(text = "1")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = stringResource(R.string.loop_accept_nav_label),
                            modifier = Modifier.size(NAV_ICON_SIZE)
                        )
                    }
                },
                label = { Text(text = stringResource(R.string.loop_accept_nav_label), style = MaterialTheme.typography.labelSmall) },
                colors = colors,
            )
        }
    }

    if (showPumpInfo && pumpInfo.isNotBlank()) {
        PumpInfoPopup(html = pumpInfo, isDark = isDark, onDismiss = { showPumpInfo = false })
    }
}

private fun GlassNavigationTab.icon(): ImageVector =
    when (this) {
        GlassNavigationTab.TREATMENTS -> Icons.Default.Fastfood
        GlassNavigationTab.PROFILES   -> IcProfile
        GlassNavigationTab.BOLUS      -> Icons.Default.Medication
        GlassNavigationTab.PUMP       -> Pump
        GlassNavigationTab.SCENARIOS  -> IcAutomation
        GlassNavigationTab.MANAGEMENT -> Icons.Default.ManageAccounts
    }

private fun GlassNavigationTab.labelRes(): Int =
    when (this) {
        GlassNavigationTab.TREATMENTS -> CoreUiR.string.treatments
        GlassNavigationTab.PROFILES   -> R.string.dashboard_glass_nav_profiles
        GlassNavigationTab.BOLUS      -> R.string.dashboard_v2_nav_bolus
        GlassNavigationTab.PUMP       -> CoreUiR.string.pump
        GlassNavigationTab.SCENARIOS  -> CoreUiR.string.scenes
        GlassNavigationTab.MANAGEMENT -> CoreUiR.string.manage
    }

/** The pump line as glass capsules above the bottom bar. Tap anywhere to close. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PumpInfoPopup(html: String, isDark: Boolean, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val lift = with(density) { (NAV_BAR_HEIGHT + 10.dp).roundToPx() }
    val parts = html.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    Popup(
        alignment = Alignment.BottomCenter,
        offset = IntOffset(0, -lift),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            parts.forEach { part ->
                val shape = RoundedCornerShape(50)
                Text(
                    text = part.replace(Regex("(?i)^\\s*(<[^>]*>)*\\s*Pump:"), "$1").htmlToAnnotatedString(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .shadow(6.dp, shape)
                        .clip(shape)
                        .background(
                            if (isDark) Brush.verticalGradient(listOf(Color(0xFF1B2A4A), Color(0xFF0E1A2E)))
                            else Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE8EEF8)))
                        )
                        .border(1.dp, if (isDark) Color(0x55FFFFFF) else Color(0xCCCBD5E1), shape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clickable { onDismiss() }
                )
            }
        }
    }
}
