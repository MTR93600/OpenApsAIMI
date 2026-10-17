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
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditorNotifier
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * System + in-app notifications when new AIMI Auditor insights are available.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AuditorNotificationManager @Inject constructor(
  private val context: Context,
  private val uiInteraction: UiInteraction,
  private val notificationManager: AapsNotificationManager,
  private val auditorStatusStateHolder: AuditorStatusStateHolder,
  private val rxBus: RxBus,
  private val rh: TextResolver,
  private val aapsLogger: AAPSLogger,
) : AimiAuditorNotifier {

  @Volatile
  private var lastNotifiedVerdictTimestampMs: Long = 0L

  init {
    createNotificationChannel()
  }

  /**
   * Posts system + in-app notifications when a new verdict is available.
   *
   * Called from `OpenAPSAIMIPlugin`, which collects the auditor state for as long as the plugin
   * runs. On `master` the old XML `OverviewFragment` and `DashboardFragment` each observed that
   * state and called this themselves; the Compose migration replaced those observers with the
   * Overview chip, which only draws a badge, and the notification quietly stopped firing. Hanging
   * it off the plugin instead of a screen also fixes the older weakness: those observers were
   * bound to `viewLifecycleOwner`, so a verdict that landed while the user was elsewhere was never
   * announced.
   *
   * Safe to call on every state. It decides for itself whether the state deserves a notification,
   * and it never announces the same verdict twice.
   *
   * @return true if notifications were posted for this verdict.
   */
  override fun showInsightAvailable(uiState: AuditorUIState): Boolean {
    if (!uiState.shouldNotify || !uiState.isActive()) return false

    val cached = AuditorVerdictCache.resolveForDisplay() ?: return false
    if (cached.cached.timestamp == lastNotifiedVerdictTimestampMs) return false

    // Both, and each one evaluated. This used to be
    // `postInAppNotification() || postSystemNotification(uiState)`, and `postInAppNotification`
    // always returns true, so `||` short-circuited and the system notification was never posted
    // once. The whole `postSystemNotification` path below was dead code, which is why nobody ever
    // saw an auditor notification outside the app. Keep the two calls on their own lines: writing
    // this back as an `||` would silently kill one of them again.
    val postedInApp = postInAppNotification()
    val postedSystem = postSystemNotification(uiState)
    val posted = postedInApp || postedSystem
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
    auditorStatusStateHolder.markAsRead()
    cancelNotification()
    val (message, _) = AuditorReportFormatter.buildFullReportMessageWithFallback(rh)
    rxBus.send(
      EventShowDialog.Ok(
        title = rh.gs(ApsStrings.aimi_auditor_report_dialog_title),
        message = message,
        onOk = onFinish,
      )
    )
  }

  private fun postInAppNotification(): Boolean {
    val text = AuditorReportFormatter.buildInAppNotificationText(rh)
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
      .setContentText(AuditorReportFormatter.buildNotificationSummary(rh, uiState))
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText(AuditorReportFormatter.buildNotificationBigText(rh, uiState)),
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
        rh.gs(ApsStrings.aimi_auditor_notification_title_warning)
      AuditorUIState.StateType.READY ->
        rh.gs(ApsStrings.aimi_auditor_notification_title_ready)
      else ->
        rh.gs(ApsStrings.aimi_auditor_notification_title_ready)
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
    // Its own request code, not 0. Android tells two PendingIntents apart with
    // `Intent.filterEquals`, which ignores the extras, so this intent and the TPO one - same
    // activity, no action on either - were a single PendingIntent while both used 0, and
    // `FLAG_UPDATE_CURRENT` let the last one posted overwrite the other's extras.
    return PendingIntent.getActivity(
      context,
      NOTIFICATION_ID,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun createOpenReportAction(): NotificationCompat.Action {
    return NotificationCompat.Action.Builder(
      CoreUiR.drawable.ic_audit_monitor,
      rh.gs(ApsStrings.aimi_auditor_notification_action_view),
      createOpenReportIntent(),
    ).build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      rh.gs(ApsStrings.aimi_auditor_notification_channel_name),
      NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
      description = rh.gs(ApsStrings.aimi_auditor_notification_channel_description)
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
