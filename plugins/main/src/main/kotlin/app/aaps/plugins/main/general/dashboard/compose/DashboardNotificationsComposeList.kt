package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState
import app.aaps.plugins.main.general.overview.notifications.NotificationStore

@Composable
internal fun DashboardNotificationsComposeList(
    composeState: DashboardEmbeddedComposeState,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val notifications = composeState.notifications
    if (notifications.isEmpty()) return
    val itemPadding = dimensionResource(R.dimen.dashboard_notification_padding)
    val itemMargin = dimensionResource(R.dimen.dashboard_notification_margin)
    if (compact) {
        val first = notifications.first()
        val line = stringResource(
            R.string.dashboard_notifications_compact_header,
            notifications.size,
            first.text,
        )
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = line,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { composeState.onDismissNotification?.invoke(first.id) },
                ) {
                    Text(
                        text = first.dismissText.ifBlank { stringResource(app.aaps.core.ui.R.string.snooze) },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(itemMargin),
    ) {
        notifications.forEach { item ->
            NotificationItemCard(
                item = item,
                itemPadding = itemPadding,
                onDismiss = { composeState.onDismissNotification?.invoke(item.id) },
            )
        }
    }
}

@Composable
private fun NotificationItemCard(
    item: NotificationStore.NotificationComposeItem,
    itemPadding: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
) {
    val bg: Color = when (item.level) {
        Notification.URGENT -> MaterialTheme.colorScheme.errorContainer
        Notification.NORMAL -> MaterialTheme.colorScheme.primaryContainer
        Notification.LOW -> MaterialTheme.colorScheme.secondaryContainer
        Notification.INFO -> MaterialTheme.colorScheme.tertiaryContainer
        Notification.ANNOUNCEMENT -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(itemPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = item.text,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
            )
            Button(onClick = onDismiss) {
                Text(item.dismissText.ifBlank { stringResource(app.aaps.core.ui.R.string.snooze) })
            }
        }
    }
}
