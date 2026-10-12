package app.aaps.plugins.aps.openAPSAIMI.tpo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.compose.navigation.PendingNavigation
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.ApsStrings
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * The Android side of [TpoNotifications]: puts a ready-made TPO notification in the tray.
 *
 * Nothing here decides anything. The words arrive already built by [TpoNotificationText], and
 * [TpoOrchestrator] has already checked whether the user wants these notifications at all. What is
 * left is pure Android: two channels, `NotificationCompat`, and the `PendingIntent` that opens the
 * AIMI screen.
 *
 * [TpoNotificationKind] says how loud each one should be; the two channels are how Android carries
 * that out. The started channel is `IMPORTANCE_DEFAULT` with its sound and vibration switched off,
 * so it appears without interrupting. The ended channel is `IMPORTANCE_HIGH` with the default
 * sound, so it peeks and is heard.
 *
 * The only text read here is the two channel names, and it comes from
 * [app.aaps.core.interfaces.resources.TextResolver] rather than `Context.getString`, so a user with
 * an AAPS language override sees one language and not two.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class TpoNotificationManager @Inject constructor(
    private val context: Context,
    private val uiInteraction: UiInteraction,
    private val rh: TextResolver,
) : TpoNotifications {
    companion object {
        private const val CHANNEL_ID_STARTED = "AIMI_TPO_PROTECTION"
        private const val CHANNEL_ID_ENDED = "AIMI_TPO_PROTECTION_ENDED"
        /** Keeps this notification's PendingIntent distinct from every other one in the app. */
        private const val PENDING_INTENT_REQUEST_CODE = 8891

        private const val NOTIFICATION_ID_STARTED = 8891
        private const val NOTIFICATION_ID_ENDED = 8892

    }

    init {
        createNotificationChannels()
    }

    override fun show(notification: TpoNotification) {
        val started = notification.kind == TpoNotificationKind.SESSION_STARTED
        val builder = NotificationCompat.Builder(context, channelOf(notification.kind))
            .setSmallIcon(CoreUiR.drawable.ic_shield)
            .setContentTitle(notification.title)
            .setContentText(notification.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.longText))
            .setPriority(if (started) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // The start notification is reposted as the session runs; only the end one should alert
            // again each time it is shown.
            .setOnlyAlertOnce(started)
            .setContentIntent(createOpenAimiPrefsIntent())
        try {
            NotificationManagerCompat.from(context).notify(idOf(notification.kind), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+
        }
    }

    override fun cancel(kind: TpoNotificationKind) {
        NotificationManagerCompat.from(context).cancel(idOf(kind))
    }

    private fun idOf(kind: TpoNotificationKind): Int = when (kind) {
        TpoNotificationKind.SESSION_STARTED -> NOTIFICATION_ID_STARTED
        TpoNotificationKind.SESSION_ENDED   -> NOTIFICATION_ID_ENDED
    }

    private fun channelOf(kind: TpoNotificationKind): String = when (kind) {
        TpoNotificationKind.SESSION_STARTED -> CHANNEL_ID_STARTED
        TpoNotificationKind.SESSION_ENDED   -> CHANNEL_ID_ENDED
    }

    private fun createOpenAimiPrefsIntent(): PendingIntent {
        val intent = Intent(context, uiInteraction.mainActivity.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PendingNavigation.EXTRA_NAVIGATE_ROUTE, OPENAPS_AIMI_PLUGIN_ROUTE)
        }
        // The request code must be ours alone. Android decides whether two PendingIntents are the
        // same with `Intent.filterEquals`, which compares the component, action, data and
        // categories - and **ignores the extras**. This intent names the main activity and sets no
        // action, and so does the auditor's; with the same request code the two were one
        // PendingIntent, and `FLAG_UPDATE_CURRENT` let whichever was posted last overwrite the
        // other's extras. The route below would then vanish and a tap would open the app at
        // whatever screen it was on, with nothing to show for it.
        return PendingIntent.getActivity(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val startedChannel = NotificationChannel(
            CHANNEL_ID_STARTED,
            rh.gs(ApsStrings.aimi_tpo_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = rh.gs(ApsStrings.aimi_tpo_notification_channel_description)
            enableVibration(false)
            setSound(null, null)
        }
        val endedChannel = NotificationChannel(
            CHANNEL_ID_ENDED,
            rh.gs(ApsStrings.aimi_tpo_notification_channel_ended_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = rh.gs(ApsStrings.aimi_tpo_notification_channel_ended_description)
        }
        notificationManager.createNotificationChannel(startedChannel)
        notificationManager.createNotificationChannel(endedChannel)
    }
}
