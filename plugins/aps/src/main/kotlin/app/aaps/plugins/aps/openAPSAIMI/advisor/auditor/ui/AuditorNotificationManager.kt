package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AIMI Auditor Notification Manager
 * 
 * Manages discrete notifications when new Auditor insights are available
 * 
 * Features:
 * - Silent notifications (no sound/vibration)
 * - BigTextStyle with insight summary
 * - Action button to open report
 * - Auto-dismiss when report opened
 * - Rate limiting (max 1 per 30 min)
 */
@Singleton
class AuditorNotificationManager @Inject constructor(
    private val context: Context,
    private val rh: ResourceHelper,
    private val injector: HasAndroidInjector
) {
    
    companion object {
        private const val CHANNEL_ID = "AIMI_AUDITOR_INSIGHTS"
        private const val CHANNEL_NAME = "AIMI Auditor Insights"
        private const val NOTIFICATION_ID = 8888  // Unique ID for Auditor
        
        private const val ACTION_OPEN_REPORT = "app.aaps.AUDITOR_OPEN_REPORT"
    }
    
    init {
        createNotificationChannel()
    }
    
    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW  // Silent, no sound
            ).apply {
                description = "Notifications when new AIMI Auditor insights are available"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(true)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show notification for new insights
     */
    fun showInsightAvailable(uiState: AuditorUIState) {
        // Don't notify if not appropriate
        if (!uiState.shouldNotify) return
        if (!uiState.isActive()) return
        
        // Build notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audit_monitor)
            .setContentTitle(getNotificationTitle(uiState))
            .setContentText(getNotificationText(uiState))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getNotificationBigText(uiState))
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)  // Silent
            .setAutoCancel(true)  // Dismiss when tapped
            .setOnlyAlertOnce(true)  // No repeat alerts
            .setContentIntent(createOpenReportIntent())
            .addAction(createOpenReportAction())
            .setColor(getNotificationColor(uiState))
            .build()
        
        // Show notification
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Fail silently if notifications disabled
        }
    }
    
    /**
     * Cancel notification (called when report opened)
     */
    fun cancelNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
    
    /**
     * Get notification title based on state
     */
    private fun getNotificationTitle(uiState: AuditorUIState): String {
        return when (uiState.type) {
            AuditorUIState.StateType.READY -> "✅ New Auditor Report"
            AuditorUIState.StateType.WARNING -> "⚠️ Important Auditor Recommendation"
            else -> "📊 Auditor Insight"
        }
    }
    
    /**
     * Get notification text (summary)
     */
    private fun getNotificationText(uiState: AuditorUIState): String {
        val count = uiState.insightCount
        return if (count > 1) {
            "$count recommendations available"
        } else {
            "New recommendation available"
        }
    }
    
    /**
     * Get notification big text (detailed)
     */
    private fun getNotificationBigText(uiState: AuditorUIState): String {
        val base = getNotificationText(uiState)
        return "$base\n\n${uiState.statusMessage}\n\nTap to view full report"
    }
    
    /**
     * Get notification color based on type
     */
    private fun getNotificationColor(uiState: AuditorUIState): Int {
        val colorRes = when (uiState.type) {
            AuditorUIState.StateType.READY -> app.aaps.core.ui.R.color.inRange
            AuditorUIState.StateType.WARNING -> app.aaps.core.ui.R.color.warning
            else -> app.aaps.core.ui.R.color.examinedProfile
        }
        return context.getColor(colorRes)
    }
    
    /**
     * Create PendingIntent to open Auditor report
     * TODO: Replace with actual AuditorVerdictActivity intent
     */
    private fun createOpenReportIntent(): PendingIntent {
        // For now, use a generic intent
        // Replace with actual deep link to AuditorVerdictActivity
        val intent = Intent(ACTION_OPEN_REPORT).apply {
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    /**
     * Create "View Report" action button
     */
    private fun createOpenReportAction(): NotificationCompat.Action {
        return NotificationCompat.Action.Builder(
            R.drawable.ic_audit_monitor,
            "View Report",
            createOpenReportIntent()
        ).build()
    }
}
