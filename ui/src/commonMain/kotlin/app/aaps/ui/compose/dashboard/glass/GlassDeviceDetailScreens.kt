package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/*
 * The Glass pump, battery and sensor quality screens. Ports of `GlassPumpDetailScreen`,
 * `GlassBatteryDetailScreen` and `GlassSensorQualityScreen` on `dev_OAPSAIMI`. Display only.
 */

private const val NO_VALUE = "--"

/** Card colors of the DEV pump and battery screens. */
private fun detailCardColor(isDark: Boolean): Color = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)

/** Back arrow and title row shared by the Glass information screens. */
@Composable
internal fun GlassDetailHeader(title: TextRef, isDark: Boolean, onBack: () -> Unit) =
    GlassDetailHeader(title = stringResource(title), isDark = isDark, onBack = onBack)

/** Back arrow, title and optional [actions] (at the end of the row) of a Glass screen. */
@Composable
internal fun GlassDetailHeader(
    title: String,
    isDark: Boolean,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val textBright = GlassColors.textBright(isDark)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back), tint = textBright)
        }
        Text(text = title, color = textBright, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun GlassPumpDetailScreen(onBack: () -> Unit, viewModel: GlassDeviceDetailViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlassSingleValueScreen(
        title = UiStrings.dashboard_glass_pump_detail_title,
        label = CoreUiStrings.reservoir_label,
        value = state.reservoirText ?: NO_VALUE,
        onBack = onBack,
    )
}

@Composable
fun GlassBatteryDetailScreen(onBack: () -> Unit, viewModel: GlassDeviceDetailViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlassSingleValueScreen(
        title = UiStrings.dashboard_glass_battery_detail_title,
        label = CoreUiStrings.battery_label,
        value = state.batteryText ?: NO_VALUE,
        onBack = onBack,
    )
}

@Composable
private fun GlassSingleValueScreen(title: TextRef, label: TextRef, value: String, onBack: () -> Unit) {
    val isDark = isGlassDarkMode()
    GlassScreenBackground(isDark = isDark) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassDetailHeader(title = title, isDark = isDark, onBack = onBack)
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(detailCardColor(isDark)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = stringResource(label), color = GlassColors.textMuted(isDark), fontSize = 12.sp)
                Text(text = value, color = GlassColors.textBright(isDark), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun GlassSensorQualityScreen(onBack: () -> Unit, viewModel: GlassDeviceDetailViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlassSensorQualityContent(quality = state.sensorQuality, onBack = onBack)
}

@Composable
internal fun GlassSensorQualityContent(quality: GlassSensorQuality?, onBack: () -> Unit) {
    val isDark = isGlassDarkMode()
    val textBright = GlassColors.textBright(isDark)
    val textMuted = GlassColors.textMuted(isDark)
    val tierColor = when (quality?.tier) {
        AdaptiveSmoothingQualityTier.OK        -> GlassColors.emerald
        AdaptiveSmoothingQualityTier.UNCERTAIN -> GlassColors.amber
        AdaptiveSmoothingQualityTier.BAD       -> GlassColors.red
        null                                   -> textMuted
    }
    GlassScreenBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassDetailHeader(title = UiStrings.dashboard_glass_sensor_quality_title, isDark = isDark, onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(GlassColors.cardBgStart(isDark), GlassColors.cardBgEnd(isDark))))
                    .border(1.dp, tierColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val badge = quality?.badgeText.orEmpty()
                val message = quality?.dialogMessage.orEmpty()
                if (badge.isBlank() && message.isBlank()) {
                    Text(text = stringResource(UiStrings.dashboard_glass_sensor_quality_empty), color = textMuted, fontSize = 13.sp)
                } else {
                    if (badge.isNotBlank()) Text(text = badge, color = tierColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (message.isNotBlank()) Text(text = message, color = textBright, fontSize = 13.sp)
                }
            }
        }
    }
}
