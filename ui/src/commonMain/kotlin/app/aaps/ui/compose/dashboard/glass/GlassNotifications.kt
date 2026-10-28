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

private val ANNOUNCEMENT_VIOLET = Color(0xFF8B5CF6)

// ==========================================
// NOTIFICATION: PUMP STATUS
// ==========================================

/**
 * The pump line under the charts: "Pump: Reservoir … | Site … | Sensor …". As on DEV, "pump
 * unreachable" is red, a site older than 3 days yellow and a sensor older than 8 days orange.
 */
@Composable
internal fun PumpStatusNotification(
    status: GlassPumpStatusLine,
    isDark: Boolean
) {
    val reservoir = AnnotatedString(status.reservoirText)
    val reservoirPart = status.unreachableText?.let { unreachable ->
        formatAnnotated(
            stringResource(UiStrings.dashboard_glass_pump_reservoir_unreachable),
            listOf(reservoir, AnnotatedString(unreachable, SpanStyle(color = GlassColors.red))),
        )
    } ?: reservoir
    val text = formatAnnotated(
        stringResource(UiStrings.dashboard_glass_pump_status_line),
        listOf(
            reservoirPart,
            status.siteAgeText.colored(GlassColors.metricAttention.takeIf { status.siteAgeWarning }),
            status.sensorAgeText.colored(GlassColors.amber.takeIf { status.sensorAgeWarning }),
        ),
    )

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
            letterSpacing = 0.3.sp
        )
    }
}

// ==========================================
// SYSTEM NOTIFICATIONS (Alarms, Alerts)
// ==========================================

/** Colour and short label of a notification card, by level. Same mapping as on `dev_OAPSAIMI`. */
internal data class GlassNotificationStyle(val color: Color, val label: TextRef)

internal fun NotificationLevel.glassStyle(): GlassNotificationStyle = when (this) {
    NotificationLevel.URGENT       -> GlassNotificationStyle(GlassColors.red, UiStrings.dashboard_glass_notification_alert)
    NotificationLevel.IMPORTANT,
    NotificationLevel.NORMAL       -> GlassNotificationStyle(GlassColors.amber, UiStrings.dashboard_glass_notification_alert)
    NotificationLevel.LOW          -> GlassNotificationStyle(GlassColors.skyBlue, UiStrings.dashboard_glass_notification_notice)
    NotificationLevel.INFO         -> GlassNotificationStyle(GlassColors.emerald, UiStrings.dashboard_glass_notification_info)
    NotificationLevel.ANNOUNCEMENT -> GlassNotificationStyle(ANNOUNCEMENT_VIOLET, UiStrings.dashboard_glass_notification_notice)
}

/**
 * The in-app notifications as Glass cards. A card has one button: the notification's own action when
 * it has one (it runs the action, reports it with [onActionClick], then removes the card), otherwise
 * Snooze, which removes the card, as on `dev_OAPSAIMI`.
 */
@Composable
internal fun NotificationsSection(
    notifications: List<AapsNotification>,
    isDark: Boolean,
    onDismiss: (AapsNotification) -> Unit,
    onActionClick: (AapsNotification) -> Unit,
    modifier: Modifier = Modifier
) {
    val dateUtil = LocalDateUtil.current
    val cardBg = if (isDark) Color(0xFF1E293B) else Color.White
    val textColor = GlassColors.textBright(isDark)
    val textMuted = GlassColors.textMuted(isDark)
    val snoozeBg = if (isDark) Color(0xFF334155) else Color(0xFFF1F5F9)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        notifications.forEach { notification ->
            val style = notification.level.glassStyle()
            val action = notification.actions.firstOrNull()

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cardBg,
                border = BorderStroke(2.dp, style.color),
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
                            .background(style.color.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = style.color,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(style.label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = style.color,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = stringResource(UiStrings.dashboard_glass_notification_text, dateUtil.timeString(notification.date), notification.text),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        onClick = {
                            if (action != null) {
                                action.action()
                                onActionClick(notification)
                            }
                            onDismiss(notification)
                        },
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
                                text = stringResource(action?.buttonText ?: CoreUiStrings.snooze),
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

private fun String.colored(color: Color?): AnnotatedString =
    if (color == null) AnnotatedString(this) else AnnotatedString(this, SpanStyle(color = color))
