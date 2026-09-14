package app.aaps.plugins.main.general.dashboard.glass

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
import app.aaps.plugins.main.general.dashboard.labelRes

private val LightBg = Color(0xFFF1F5F9)
private val LightCard = Color(0xFFFFFFFF)
private val LightPrimary = Color(0xFF0D1B2A)
private val LightAccent = Color(0xFF00B894)
private val LightMuted = Color(0xFF64748B)

private val DarkBg = Color(0xFF213145)
private val DarkCard = Color(0xFF0F1C2C)
private val DarkPrimary = Color(0xFFEAF1FF)
private val DarkAccent = Color(0xFF6DFAD2)
private val DarkMuted = Color(0xFF778598)

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
    val card = if (isDark) DarkCard else LightCard
    val primary = if (isDark) DarkPrimary else LightPrimary
    val accent = if (isDark) DarkAccent else LightAccent
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
                text = stringResource(R.string.dashboard_glass_personalize_empty_hint),
                color = muted,
                fontSize = 13.sp,
            )
        }
        Text(
            text = stringResource(R.string.dashboard_glass_personalize_section_main_screen),
            color = muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        GLASS_PILL_CATALOG.forEach { entry ->
            val checked = entry.id in selectedPills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(card)
                    .clickable { onToggle(entry.id, !checked) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(entry.labelRes),
                    color = primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle(entry.id, it) },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                )
            }
        }
        Text(
            text = stringResource(R.string.dashboard_glass_personalize_section_tools),
            color = muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        DashboardV2ToolAction.entries.forEach { action ->
            val checked = action in selectedTools
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(card)
                    .clickable { onToolToggle(action, !checked) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(action.labelRes()),
                    color = primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToolToggle(action, it) },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                )
            }
        }
    }
}
