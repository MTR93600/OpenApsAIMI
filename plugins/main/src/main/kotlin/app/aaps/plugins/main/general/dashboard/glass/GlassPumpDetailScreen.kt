package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel

@Composable
fun GlassPumpDetailScreen(
    overviewViewModel: OverviewViewModel,
    onBack: () -> Unit,
    isDark: Boolean,
) {
    val status by overviewViewModel.statusCardState.observeAsState()
    val bg = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val card = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val primary = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)
    val muted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = primary)
                }
                Text(
                    text = stringResource(R.string.dashboard_glass_pump_detail_title),
                    color = primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_v2_reservoir),
                    color = muted,
                    fontSize = 12.sp,
                )
                Text(
                    text = status?.reservoirText ?: "--",
                    color = primary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
