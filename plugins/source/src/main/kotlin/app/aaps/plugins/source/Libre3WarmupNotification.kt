package app.aaps.plugins.source

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.plugins.libre3.Libre3WarmupState
import app.aaps.plugins.source.activities.Libre3WarmupActivity
import app.aaps.plugins.source.compose.Libre3WarmupCountdown
import app.aaps.core.ui.R as CoreUiR

/**
 * The ongoing status bar message about the sensor.
 *
 * It lets the user leave the warm-up screen and go back to the dashboard while the driver keeps
 * working. It shows the honest phase, and it clears itself when the sensor is running or when
 * nothing is going on.
 */
class Libre3WarmupNotification(private val context: Context) {

    init {
        createChannel()
    }

    /** Shows [state], or clears the message when there is nothing to say. */
    fun update(state: Libre3WarmupState) {
        if (state.phase == Libre3WarmupState.Phase.IDLE || state.phase == Libre3WarmupState.Phase.READY) {
            cancel()
            return
        }

        val ongoing = state.phase != Libre3WarmupState.Phase.FAILED
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(CoreUiR.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.libre3_notif_title))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(openWarmupScreen())

        when (state.phase) {
            Libre3WarmupState.Phase.PAIRING      ->
                builder.setContentText(context.getString(R.string.libre3_notif_pairing))
                    .setProgress(0, 0, true)
                    .setShowWhen(false)

            Libre3WarmupState.Phase.CONNECTING   ->
                builder.setContentText(context.getString(R.string.libre3_notif_connecting))
                    .setProgress(0, 0, true)
                    .setShowWhen(false)

            Libre3WarmupState.Phase.RECONNECTING ->
                builder.setContentText(context.getString(R.string.libre3_notif_reconnecting))
                    .setProgress(0, 0, true)
                    .setShowWhen(false)

            Libre3WarmupState.Phase.WARMING      -> {
                builder.setContentTitle(context.getString(R.string.libre3_notif_warming_title))
                    .setContentText(context.getString(R.string.libre3_notif_warming_text))
                val remaining = Libre3WarmupCountdown.remainingMs(state, System.currentTimeMillis())
                if (remaining != null && remaining > 0L) {
                    // Android counts down by itself, so the message does not have to be rewritten
                    // every second.
                    builder.setWhen(state.endsAtEpochMs ?: (System.currentTimeMillis() + remaining))
                        .setShowWhen(true)
                        .setUsesChronometer(true)
                        .setChronometerCountDown(true)
                } else {
                    // Nothing is known, so nothing is promised.
                    builder.setProgress(0, 0, true).setShowWhen(false)
                }
            }

            Libre3WarmupState.Phase.FAILED       ->
                builder.setContentTitle(context.getString(R.string.libre3_notif_failed_title))
                    .setContentText(context.getString(R.string.libre3_notif_failed_text))
                    .setShowWhen(false)

            else                                 -> Unit
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // The user did not allow notifications. The session carries on without one.
        }
    }

    fun cancel() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // Nothing to clear, or notifications are not allowed.
        }
    }

    private fun openWarmupScreen(): PendingIntent {
        val intent = Intent(context, Libre3WarmupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.libre3_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.libre3_notif_channel_desc)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {

        const val CHANNEL_ID = "libre3_sensor_status"
        const val NOTIFICATION_ID = 4471
    }
}
