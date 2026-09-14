package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top bar of the Glass dashboard: Stats pill, hour range dock, Treatment pill.
 * Ported from the reference GlassOverviewScreen.kt (TimeFilterBar / GlassHourDock /
 * GlassActionPill). Visual design is unchanged; only label wiring was adapted so the
 * caller supplies translated strings instead of hardcoded English literals.
 */
@Composable
internal fun TimeFilterBar(
    selectedHours: Int,
    onSelectHours: (Int) -> Unit,
    onOpenStats: () -> Unit,
    onOpenTreatment: () -> Unit,
    statsLabel: String,
    treatmentLabel: String,
    isDark: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stats button (action pill, same visual as the IOB/Target/Basal pills)
        GlassActionPill(
            label = statsLabel,
            onClick = onOpenStats,
            isDark = isDark,
            height = 40.dp
        )

        // Range selector [ 6h | 12h | 18h | 24h ] inside a glass dock
        GlassHourDock(
            selectedHours = selectedHours,
            onSelectHours = onSelectHours,
            isDark = isDark
        )

        // Treatment button (action pill, same visual as the IOB/Target/Basal pills)
        GlassActionPill(
            label = treatmentLabel,
            onClick = onOpenTreatment,
            isDark = isDark,
            height = 40.dp
        )
    }
}

@Composable
internal fun GlassHourDock(
    selectedHours: Int,
    onSelectHours: (Int) -> Unit,
    isDark: Boolean
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .shadow(
                elevation = if (isDark) 8.dp else 5.dp,
                shape = shape,
                spotColor = if (isDark) Color.Black.copy(alpha = 0.45f) else Color(0x20000000)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (isDark) {
                        listOf(Color(0x33FFFFFF), Color(0x0EFFFFFF))
                    } else {
                        listOf(Color(0x99FFFFFF), Color(0x55E8EEF8))
                    }
                )
            )
            .border(1.dp, if (isDark) Color(0x33FFFFFF) else Color(0x80CBD5E1), shape)
            .height(40.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(6, 12, 18, 24).forEach { hours ->
                val isSelected = selectedHours == hours
                GlassActionPill(
                    label = "${hours}h",
                    onClick = { onSelectHours(hours) },
                    isDark = isDark,
                    isSelected = isSelected,
                    compact = true
                )
            }
        }
    }
}

@Composable
internal fun GlassActionPill(
    label: String,
    onClick: () -> Unit,
    isDark: Boolean,
    isSelected: Boolean = false,
    compact: Boolean = false,
    height: Dp? = null
) {
    val shape = when {
        compact -> RoundedCornerShape(14.dp)
        else -> RoundedCornerShape(50)
    }
    val bgBrush = when {
        isSelected && isDark -> Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0284C7)))
        isSelected -> Brush.verticalGradient(listOf(Color(0xFF38BDF8), Color(0xFF0284C7)))
        isDark -> Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x0EFFFFFF)))
        else -> Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0xE0EEF2FA)))
    }
    val borderColor = when {
        isSelected -> Color(0x66028AC6)
        isDark -> Color(0x26FFFFFF)
        else -> Color(0xB8CBD5E1)
    }
    val textColor = when {
        isSelected -> Color.White
        isDark -> Color(0xFFE2E8F0)
        else -> Color(0xFF1E293B)
    }

    val boxModifier = Modifier
            .shadow(
                elevation = if (isDark) 7.dp else 5.dp,
                shape = shape,
                spotColor = if (isDark) Color.Black.copy(alpha = 0.42f) else Color(0x20000000)
            )
        .clip(shape)
        .background(bgBrush)
        .border(1.dp, borderColor, shape)
        .clickable { onClick() }
        .padding(
            vertical = when {
                height != null -> 0.dp
                compact -> 6.dp
                else -> 14.dp
            },
            horizontal = when {
                compact -> 8.dp
                else -> 20.dp
            }
        )
        .then(if (height != null) Modifier.height(height) else Modifier)

    Box(
        modifier = boxModifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = when {
                compact -> 11.sp
                else -> 14.sp
            },
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
    }
}
