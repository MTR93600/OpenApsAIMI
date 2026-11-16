package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

// ==========================================
// NOTIFICATION: PUMP STATUS
// ==========================================

/**
 * The pump line under the charts: "Pump: … | Site … | Sensor …". The reservoir is red while the pump
 * is unreachable, a site older than 3 days yellow and a sensor older than 8 days orange.
 *
 * The words "pump unreachable" are not shown, so the line stays on one row on a small phone; only
 * the color says it. A screen reader still hears them, through the content description.
 */
@Composable
internal fun PumpStatusNotification(
    status: GlassPumpStatusLine,
    isDark: Boolean
) {
    val siteAge = status.siteAgeText.colored(GlassColors.metricAttention.takeIf { status.siteAgeWarning })
    val sensorAge = status.sensorAgeText.colored(GlassColors.amber.takeIf { status.sensorAgeWarning })
    val text = formatAnnotated(
        stringResource(UiStrings.dashboard_glass_pump_status_line),
        listOf(
            status.reservoirText.colored(GlassColors.red.takeIf { status.unreachableText != null }),
            siteAge,
            sensorAge,
        ),
    )
    val spoken = status.unreachableText?.let { unreachable ->
        stringResource(
            UiStrings.dashboard_glass_pump_status_line,
            stringResource(UiStrings.dashboard_glass_pump_reservoir_unreachable, status.reservoirText, unreachable),
            status.siteAgeText,
            status.sensorAgeText,
        )
    }

    val shape = RoundedCornerShape(14.dp)
    val bgBrush = if (isDark) {
        Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x14FFFFFF)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xE4EEF2FA)))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape)
            .clip(shape)
            .background(bgBrush)
            .border(1.dp, if (isDark) Color(0x30FFFFFF) else Color(0xD0CBD5E1), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
            letterSpacing = 0.3.sp,
            modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier
        )
    }
}

private fun String.colored(color: Color?): AnnotatedString =
    if (color == null) AnnotatedString(this) else AnnotatedString(this, SpanStyle(color = color))
