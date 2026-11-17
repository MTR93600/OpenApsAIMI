package app.aaps.ui.compose.notificationsSheet

import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.NotificationLevel

/**
 * The most urgent level among [this] notifications (URGENT before IMPORTANT, and so on). The bell takes its
 * colour from it. Null when there is no notification.
 */
fun List<AapsNotification>.mostUrgentLevel(): NotificationLevel? = minByOrNull { it.level.priority }?.level
