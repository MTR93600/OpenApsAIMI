package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager as AapsNotificationManager
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * System + in-app notifications when new AIMI Auditor insights are available.
 */
@SingleIn(AppScope::class)
class AuditorNotificationManager @Inject constructor(
  private val context: Context,
  private val uiInteraction: UiInteraction,
  private val notificationManager: AapsNotificationManager,
  private val auditorStatusLiveData: AuditorStatusLiveData,
  private val rxBus: RxBus,
  private val aapsLogger: AAPSLogger,
) {

  @Volatile
  private var lastNotifiedVerdictTimestampMs: Long = 0L

  init {
    createNotificationChannel()
  }

  /**
   * Posts system + in-app notifications when a new verdict is available.
   * @return true if notifications were posted for this verdict.
   */
  fun showInsightAvailable(uiState: AuditorUIState): Boolean {
    if (!uiState.shouldNotify || !uiState.isActive()) return false

    val cached = AuditorVerdictCache.resolveForDisplay() ?: return false
    if (cached.cached.timestamp == lastNotifiedVerdictTimestampMs) return false

    val posted = postInAppNotification() || postSystemNotification(uiState)
    if (posted) {
      lastNotifiedVerdictTimestampMs = cached.cached.timestamp
    }
    return posted
  }

  fun cancelNotification() {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    notificationManager.dismiss(NotificationId.AIMI_AUDITOR_INSIGHT)
  }

  /**
   * Presents the full auditor report dialog and clears notification state.
   */
  fun openReport(onFinish: (() -> Unit)? = null) {
    auditorStatusLiveData.markAsRead()
    cancelNotification()
    val (message, _) = AuditorReportFormatter.buildFullReportMessageWithFallback(context)
    rxBus.send(
      EventShowDialog.Ok(
        title = context.getString(R.string.aimi_auditor_report_dialog_title),
        message = message,
        onOk = onFinish,
      )
    )
  }

  private fun postInAppNotification(): Boolean {
    val text = AuditorReportFormatter.buildInAppNotificationText(context)
    notificationManager.post(
      id = NotificationId.AIMI_AUDITOR_INSIGHT,
      text = text,
      level = NotificationLevel.INFO,
      validMinutes = 60,
      actions = listOf(
        NotificationAction(ApsStrings.aimi_auditor_notification_action_view) {
          launchReportActivity()
        },
      ),
      validityCheck = {
        AuditorVerdictCache.resolveForDisplay() != null
      },
    )
    return true
  }

  private fun postSystemNotification(uiState: AuditorUIState): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
      aapsLogger.warn(LTag.NOTIFICATION, "AIMI Auditor system notification skipped — notifications disabled")
      return false
    }

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(CoreUiR.drawable.ic_audit_monitor)
      .setContentTitle(getNotificationTitle(uiState))
      .setContentText(AuditorReportFormatter.buildNotificationSummary(context, uiState))
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(AuditorReportFormatter.buildNotificationBigText(context, uiState)),
      )
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(createOpenReportIntent())
      .addAction(createOpenReportAction())
      .setColor(getNotificationColor(uiState))
      .build()

    return try {
      NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
      true
    } catch (e: SecurityException) {
      aapsLogger.warn(
        LTag.NOTIFICATION,
        "AIMI Auditor system notification failed — POST_NOTIFICATIONS likely denied",
        e,
      )
      false
    }
  }

  private fun getNotificationTitle(uiState: AuditorUIState): String {
    return when (uiState.type) {
      AuditorUIState.StateType.WARNING ->
        context.getString(R.string.aimi_auditor_notification_title_warning)
      AuditorUIState.StateType.READY ->
        context.getString(R.string.aimi_auditor_notification_title_ready)
      else ->
        context.getString(R.string.aimi_auditor_notification_title_ready)
    }
  }

  private fun getNotificationColor(uiState: AuditorUIState): Int {
    val colorRes = when (uiState.type) {
      AuditorUIState.StateType.READY -> CoreUiR.color.inRange
      AuditorUIState.StateType.WARNING -> CoreUiR.color.warning
      else -> CoreUiR.color.examinedProfile
    }
    return context.getColor(colorRes)
  }

  private fun launchReportActivity() {
    val intent = Intent(context, uiInteraction.mainActivity.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
  }

  private fun createOpenReportIntent(): PendingIntent {
    val intent = Intent(context, uiInteraction.mainActivity.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun createOpenReportAction(): NotificationCompat.Action {
    return NotificationCompat.Action.Builder(
      CoreUiR.drawable.ic_audit_monitor,
      context.getString(R.string.aimi_auditor_notification_action_view),
      createOpenReportIntent(),
    ).build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      context.getString(R.string.aimi_auditor_notification_channel_name),
      NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
      description = context.getString(R.string.aimi_auditor_notification_channel_description)
      enableVibration(false)
      setShowBadge(true)
    }
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(channel)
  }

  companion object {
    private const val CHANNEL_ID = "AIMI_AUDITOR_INSIGHTS_V2"
    private const val NOTIFICATION_ID = 8888
  }
}
