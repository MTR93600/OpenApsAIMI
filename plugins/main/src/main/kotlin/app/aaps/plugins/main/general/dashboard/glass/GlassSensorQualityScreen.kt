package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel

@Composable
fun GlassSensorQualityScreen(
    overviewViewModel: OverviewViewModel,
    onBack: () -> Unit,
    isDark: Boolean,
) {
    val status by overviewViewModel.statusCardState.observeAsState()

    val bgColorTop = if (isDark) Color(0xFF070E1B) else Color(0xFFF1F5F9)
    val bgColorBot = if (isDark) Color(0xFF0B1424) else Color(0xFFE8EEF8)
    val cardBgStart = if (isDark) Color(0x24FFFFFF) else Color(0xF5FFFFFF)
    val cardBgEnd = if (isDark) Color(0x0EFFFFFF) else Color(0xE0EEF2FA)
    val textBright = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    val tier = status?.adaptiveSmoothingQualityTier
    val tierColor = when (tier) {
        AdaptiveSmoothingQualityTier.OK -> Color(0xFF10B981)
        AdaptiveSmoothingQualityTier.UNCERTAIN -> Color(0xFFF59E0B)
        AdaptiveSmoothingQualityTier.BAD -> Color(0xFFEF4444)
        null -> textMuted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgColorTop, bgColorBot, bgColorTop)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = textBright)
                }
                Text(
                    text = stringResource(R.string.dashboard_glass_sensor_quality_title),
                    color = textBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(cardBgStart, cardBgEnd)))
                    .border(1.dp, tierColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (status?.adaptiveSmoothingQualityBadgeText.isNullOrBlank() && status?.adaptiveSmoothingQualityDialogMessage.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.dashboard_glass_sensor_quality_empty),
                        color = textMuted,
                        fontSize = 13.sp
                    )
                } else {
                    if (!status?.adaptiveSmoothingQualityBadgeText.isNullOrBlank()) {
                        Text(
                            text = status?.adaptiveSmoothingQualityBadgeText.orEmpty(),
                            color = tierColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!status?.adaptiveSmoothingQualityDialogMessage.isNullOrBlank()) {
                        Text(
                            text = status?.adaptiveSmoothingQualityDialogMessage.orEmpty(),
                            color = textBright,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
