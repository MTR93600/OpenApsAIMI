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
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * User-visible notification when a TPO protection session starts or ends.
 * Controlled by [BooleanKey.OApsAIMITpoNotifyOnApply].
 *
 * This is the Android side of [TpoNotifications]. Everything here is platform work - channels,
 * `NotificationCompat`, the `PendingIntent` that opens the AIMI screen - which is why it stays in
 * `androidMain` while the orchestrator that decides when to call it is shared.
 *
 * The **text** is not platform work, so none of it goes through `Context.getString`. Every line comes
 * from [app.aaps.core.interfaces.resources.TextResolver], which exists on every target and which the
 * rest of the app already uses. One notification used to take its pack label from the resolver and
 * everything else from the raw `Context`; those two do not answer in the same language when the user
 * has set an AAPS language override, so a single notification could be half translated.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class TpoNotificationManager @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val uiInteraction: UiInteraction,
    private val rh: TextResolver,
) : TpoNotifications {
    companion object {
        private const val CHANNEL_ID_STARTED = "AIMI_TPO_PROTECTION"
        private const val CHANNEL_ID_ENDED = "AIMI_TPO_PROTECTION_ENDED"
        private const val NOTIFICATION_ID_STARTED = 8891
        private const val NOTIFICATION_ID_ENDED = 8892
        private const val OPENAPS_AIMI_PLUGIN_ROUTE = "plugin_preferences/OpenAPSAIMIPlugin"
        private const val EXTRA_NAVIGATE_ROUTE = "extra_navigate_route"
    }

    init {
        createNotificationChannels()
    }

    override fun showSessionStarted(session: TpoSessionDocument) {
        if (!preferences.get(BooleanKey.OApsAIMITpoNotifyOnApply)) return
        if (session.status != TpoSessionStatus.ACTIVE) return

        val ui = TpoUiSupport.buildActiveSessionUi(session, aimiWallClockMs()) ?: return
        val packLabel = rh.gs(ui.packTitle)
        val title = rh.gs(ApsStrings.aimi_tpo_notification_started_title)
        val text = rh.gs(
            ApsStrings.aimi_tpo_notification_started_text,
            packLabel,
            ui.remainingMinutes,
            ui.changedKeyCount,
        )
        val bigText = buildString {
            append(text)
            append('\n')
            append(rh.gs(ApsStrings.aimi_tpo_notification_started_tier, ui.tierLabel))
            if (ui.deltaPreviewLines.isNotEmpty()) {
                append('\n')
                ui.deltaPreviewLines.forEach { line ->
                    append(line)
                    append('\n')
                }
            }
            if (ui.extraChangeCount > 0) {
                append(rh.gs(ApsStrings.aimi_tpo_extra_changes, ui.extraChangeCount))
            }
        }.trim()

        postNotification(
            title = title,
            text = text,
            bigText = bigText,
            notificationId = NOTIFICATION_ID_STARTED,
            channelId = CHANNEL_ID_STARTED,
            onlyAlertOnce = true,
            priority = NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    override fun showSessionEnded(reason: TpoEndReason) {
        if (!preferences.get(BooleanKey.OApsAIMITpoNotifyOnApply)) return
        cancelStartedNotification()
        val title = rh.gs(ApsStrings.aimi_tpo_notification_ended_title)
        val text = when (reason) {
            TpoEndReason.EXPIRED -> rh.gs(ApsStrings.aimi_tpo_notification_ended_expired)
            TpoEndReason.MANUAL_REVERT -> rh.gs(ApsStrings.aimi_tpo_notification_ended_manual)
            TpoEndReason.SUPERSEDED -> rh.gs(ApsStrings.aimi_tpo_notification_ended_superseded)
        }
        postNotification(
            title = title,
            text = text,
            bigText = text,
            notificationId = NOTIFICATION_ID_ENDED,
            channelId = CHANNEL_ID_ENDED,
            onlyAlertOnce = false,
            priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    fun cancelNotification() {
        cancelStartedNotification()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_ENDED)
    }

    private fun cancelStartedNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_STARTED)
    }

    private fun postNotification(
        title: String,
        text: String,
        bigText: String,
        notificationId: Int,
        channelId: String,
        onlyAlertOnce: Boolean,
        priority: Int,
    ) {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(CoreUiR.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(priority)
            .setAutoCancel(true)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setContentIntent(createOpenAimiPrefsIntent())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+
        }
    }

    private fun createOpenAimiPrefsIntent(): PendingIntent {
        val intent = Intent(context, uiInteraction.mainActivity.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_NAVIGATE_ROUTE, OPENAPS_AIMI_PLUGIN_ROUTE)
        }
        return PendingIntent.getActivity(
            context,
            0,
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
