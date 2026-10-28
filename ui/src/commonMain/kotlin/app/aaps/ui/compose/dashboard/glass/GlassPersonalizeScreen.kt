package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

private val LightBg = Color(0xFFF1F5F9)
private val LightCard = Color(0xFFFFFFFF)
private val LightPrimary = Color(0xFF0D1B2A)
private val LightMuted = Color(0xFF64748B)

private val DarkBg = Color(0xFF213145)
private val DarkCard = Color(0xFF0F1C2C)
private val DarkPrimary = Color(0xFFEAF1FF)
private val DarkMuted = Color(0xFF778598)

/** Accent of the Personalize and Tools overlays: the checkboxes and the text buttons. */
internal fun glassOverlayAccent(isDark: Boolean): Color = if (isDark) Color(0xFF6DFAD2) else Color(0xFF00B894)

/** Choice of the pills shown on the Glass home and of the tiles shown on its Tools screen. */
@Composable
internal fun GlassPersonalizeScreen(
    selectedPills: Set<GlassPillId>,
    onToggle: (GlassPillId, Boolean) -> Unit,
    selectedTools: Set<DashboardV2ToolAction>,
    onToolToggle: (DashboardV2ToolAction, Boolean) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (isDark) DarkBg else LightBg
    val muted = if (isDark) DarkMuted else LightMuted

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selectedPills.isEmpty()) {
            Text(
                text = stringResource(UiStrings.dashboard_glass_personalize_empty_hint),
                color = muted,
                fontSize = 13.sp,
            )
        }
        SectionTitle(UiStrings.dashboard_glass_personalize_section_main_screen, muted)
        GLASS_PILL_CATALOG.forEach { entry ->
            val checked = entry.id in selectedPills
            CheckRow(label = entry.label, checked = checked, isDark = isDark, onCheckedChange = { onToggle(entry.id, it) })
        }
        SectionTitle(UiStrings.dashboard_glass_personalize_section_tools, muted)
        DashboardV2ToolAction.entries.forEach { action ->
            val checked = action in selectedTools
            CheckRow(label = action.label, checked = checked, isDark = isDark, onCheckedChange = { onToolToggle(action, it) })
        }
    }
}

@Composable
private fun SectionTitle(text: TextRef, color: Color) {
    Text(
        text = stringResource(text),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CheckRow(label: TextRef, checked: Boolean, isDark: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (isDark) DarkCard else LightCard)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(label),
            color = if (isDark) DarkPrimary else LightPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = glassOverlayAccent(isDark)),
        )
    }
}
