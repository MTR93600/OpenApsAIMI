package app.aaps.plugins.main.general.dashboard.glass

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.compose.htmlToAnnotatedString
import app.aaps.plugins.main.general.overview.notifications.NotificationStore

// ==========================================
// NOTIFICATION: PUMP STATUS (SMB, Temp Basal, etc.)
// ==========================================
@Composable
internal fun PumpStatusNotification(
    status: String,
    isDark: Boolean
) {
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
            text = status.htmlToAnnotatedString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
            letterSpacing = 0.3.sp
        )
    }
}

// ==========================================
// SYSTEM NOTIFICATIONS (Alarms, Alerts)
// ==========================================
@Composable
internal fun NotificationsSection(
    notifications: List<NotificationStore.NotificationComposeItem>,
    isDark: Boolean,
    onDismiss: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBg = if (isDark) Color(0xFF1E293B) else Color.White
    val textColor = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val snoozeBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        notifications.forEach { notification ->
            val (strokeColor, levelLabel) = when (notification.level) {
                0 -> Color(0xFFEF4444) to "ALERT"      // URGENT: Missing BG, Low Glucose
                1 -> Color(0xFFF59E0B) to "ALERT"      // NORMAL
                2 -> Color(0xFF38BDF8) to "NOTICE"     // LOW
                3 -> Color(0xFF10B981) to "INFO"       // INFO: Stable glucose, Calibration
                4 -> Color(0xFF8B5CF6) to "NOTICE"     // ANNOUNCEMENT
                else -> Color(0xFF94A3B8) to "NOTICE"
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                border = BorderStroke(2.dp, strokeColor),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(strokeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = strokeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = levelLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = strokeColor,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = notification.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Dismiss button
                    Surface(
                        onClick = { onDismiss(notification.id) },
                        shape = RoundedCornerShape(10.dp),
                        color = snoozeBg,
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Snooze,
                                contentDescription = null,
                                tint = textMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = notification.dismissText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}
