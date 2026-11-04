package app.aaps.wear.watchdirect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.R

/**
 * The watch's own low and urgent low alarm, for direct-to-watch, while the phone is away.
 *
 * The phone owns the glucose alarms. This one is raised only after the phone has sent nothing for
 * [WatchDirectReadings.PHONE_SILENT_MS], and only for a low the watch read itself. It is a
 * notification and an alarm vibration, which the silent mode of the watch does not block. Nothing
 * goes to the phone: the phone is away, and when it comes back its own alarms take over.
 */
class WatchDirectLowAlarm(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val vibrator: Vibrator? get() = context.getSystemService(Vibrator::class.java)

    fun raise(urgent: Boolean, value: String, arrow: String) {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.watch_direct_alarm_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                // The vibration below is the alarm. The channel itself stays quiet, so it is not doubled.
                setSound(null, null)
                enableVibration(false)
            }
        )
        val text = context.getString(R.string.watch_direct_low_message, value, arrow)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_icon)
            .setContentTitle(context.getString(if (urgent) R.string.watch_direct_urgent_low else R.string.watch_direct_low))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            aapsLogger.error(LTag.WEAR, "Watch low alarm notification not allowed", e)
        }
        vibrate(urgent)
    }

    fun clear() {
        notificationManager.cancel(NOTIFICATION_ID)
        vibrator?.cancel()
    }

    private fun vibrate(urgent: Boolean) {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val timings = if (urgent) URGENT_PATTERN else LOW_PATTERN
        val effect = VibrationEffect.createWaveform(timings, -1)
        try {
            // USAGE_ALARM: the silent mode of the watch does not block an alarm vibration.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "Watch low alarm vibration failed", e)
        }
    }

    private companion object {

        const val CHANNEL_ID = "AndroidAPS-WatchDirectLow"

        // Clear of the ids DataLayerListenerServiceWear (1, 2, 556677) and the other watch notifications use.
        const val NOTIFICATION_ID = 7970

        /** About 5 s for a low, about 10 s for an urgent low. */
        val LOW_PATTERN = longArrayOf(0, 600, 400, 600, 400, 600, 400, 600, 400, 600)
        val URGENT_PATTERN = longArrayOf(0, 900, 300, 900, 300, 900, 300, 900, 300, 900, 300, 900, 300, 900, 300, 900)
    }
}
