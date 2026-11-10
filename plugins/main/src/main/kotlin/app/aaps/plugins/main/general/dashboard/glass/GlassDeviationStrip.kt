package app.aaps.plugins.main.general.dashboard.glass

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.overview.graph.DeviationDataPoint
import kotlin.math.abs

/**
 * Insulin balance strip, like the deviation bars of the original AAPS but shown only where it matters:
 * red (down) where there was too much insulin while glucose was in the lower half of the range, green (up) where there
 * was too little while glucose was in the upper half. Everything else is left empty, so a bar always means "look here".
 */
@Composable
internal fun DeviationStrip(
    bars: List<DeviationBar>,
    timeRangeHours: Float,
    historyFraction: Float,
    isDark: Boolean,
    scrubProgress: Float? = null,
    embedded: Boolean = true,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aimi_coefficient_summary", Context.MODE_PRIVATE)
    val count = prefs.getInt("count", 0)
    val titleColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val muted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDark) Color(0x18FFFFFF) else Color(0x0D0F172A))
            .clickable {
                context.startActivity(Intent().setClassName(context, "app.aaps.plugins.aps.openAPSAIMI.advisor.assistant.AimiAssistantActivity"))
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text("Анализ коэффициентов", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = titleColor)
        prefs.getString("dia_week", null)?.let {
            Text("DIA · $it", fontSize = 12.sp, color = muted)
        }
        if (count == 0) {
            Text("Нажмите, чтобы рассчитать значения", fontSize = 11.sp, color = muted, modifier = Modifier.padding(top = 5.dp))
        } else {
            repeat(count) { index ->
                val name = prefs.getString("name_$index", "") ?: ""
                val reference = prefs.getString("reference_$index", "—") ?: "—"
                val profile = prefs.getString("profile_$index", "—") ?: "—"
                val differs = prefs.getBoolean("differs_$index", false)
                Text(name, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = muted, modifier = Modifier.padding(top = 5.dp))
                Text(reference, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (differs) Color(0xFFF59E0B) else Color(0xFF22C55E))
                Text("(сейчас: $profile)", fontSize = 10.sp, color = muted)
            }
        }
    }
}

/**
 * Turns the deviation points into bars for the strip. A point becomes a bar only if it says "too much insulin" while
 * glucose is in the lower half of the range, or "too little" while it is in the upper half.
 */
internal fun buildDeviationBars(
    deviations: List<DeviationDataPoint>,
    nowEpochMs: Long,
    rangeHours: Float,
    readings: List<BgReadingPoint>,
    lowLine: Float,
    highLine: Float,
): List<DeviationBar> {
    if (deviations.isEmpty() || readings.isEmpty()) return emptyList()
    val windowStart = nowEpochMs - (rangeHours * 3_600_000f).toLong()
    val inWindow = deviations.filter { it.timestamp in windowStart..nowEpochMs }
    if (inWindow.isEmpty()) return emptyList()
    val maxAbs = inWindow.maxOf { abs(it.value) }.coerceAtLeast(0.0001)
    val midLine = (lowLine + highLine) / 2f
    return inWindow.mapNotNull { d ->
        val progress = ((d.timestamp - windowStart).toFloat() / (nowEpochMs - windowStart).toFloat()).coerceIn(0f, 1f)
        val bg = readings.minByOrNull { abs(it.progress - progress) }?.value ?: return@mapNotNull null
        val size = (abs(d.value) / maxAbs).toFloat()
        when {
            d.value < 0 && bg < midLine -> DeviationBar(progress, size, excess = true)
            d.value > 0 && bg > midLine -> DeviationBar(progress, size, excess = false)
            else -> null
        }
    }
}
