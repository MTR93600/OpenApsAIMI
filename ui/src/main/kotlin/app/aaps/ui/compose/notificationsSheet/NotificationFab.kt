package app.aaps.ui.compose.notificationsSheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.ui.compose.AapsTheme

@Composable
fun NotificationFab(
    notificationCount: Int,
    highestLevel: NotificationLevel?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = notificationCount > 0,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
    ) {
        val containerColor = highestLevel?.toColor() ?: AapsTheme.generalColors.notificationInfo
        val contentColor = AapsTheme.generalColors.onNotification

        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.border(1.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x1AFFFFFF))), CircleShape),
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 6.dp,
                pressedElevation = 12.dp,
                focusedElevation = 8.dp,
                hoveredElevation = 8.dp
            )
        ) {
            BadgedBox(
                badge = {
                    Badge {
                        Text(text = notificationCount.toString())
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null
                )
            }
        }
    }
}
