package app.aaps.plugins.source

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The missed-reading alarm, armed in advance with iOS, so it rings even when the app cannot run.
 *
 * On both phones the missed-reading alarm is `LocalAlertUtils.checkStaleBGAlert`, which the
 * periodic maintenance runs from inside the app. On Android the app keeps running, so that is
 * enough. On iPhone it is not: a suspended app runs no code at all, and the one thing that would
 * wake it - the sensor - is exactly what has gone quiet. The person would then be told nothing,
 * which is the case this alarm exists for.
 *
 * So after every reading a local notification is scheduled at "reading time + stale limit", with
 * the same text as the in-app alarm. The next reading replaces it (same identifier), so it only
 * rings when no reading came in time. iOS delivers it from the system, whether the app is running,
 * suspended or ended.
 *
 * Limits worth knowing:
 *
 * - It rings once. The in-app alarm repeats every stale limit while the app runs; this one cannot,
 *   because a repeating trigger would keep ringing after the readings are back and the app was
 *   never woken to take it down.
 * - While the app does run, the in-app alarm can ring as well, so the person may see the message
 *   twice. Twice is chosen over never.
 * - It obeys the person's notification settings for the app, like every iOS notification.
 *
 * Only ONE+ readings re-arm it, because only this plugin calls it. The plugin takes it down when it
 * stops, so switching to another BG source does not leave a stale alarm behind.
 */
internal class DexcomOnePlusMissedReadingAlarm(private val aapsLogger: AAPSLogger) {

    // Lazy, not eager: `currentNotificationCenter()` needs an app bundle and throws without one,
    // so a field initialiser would make the host impossible to build in a test binary.
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }
    private var authorizationAsked = false

    /**
     * Arm the alarm [delaySeconds] from now, replacing the one armed before.
     *
     * @param breakThroughFocus `AlertOverrideDoNotDisturb`, the same switch the in-app alarm reads.
     */
    fun arm(delaySeconds: Double, title: String, text: String, breakThroughFocus: Boolean) {
        requestAuthorizationOnce()
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(text)
            // The in-app alarm is URGENT and has a sound. iOS plays this one itself, because the
            // app is not running to play anything when it rings.
            setSound(UNNotificationSound.defaultSound())
            setInterruptionLevel(
                if (breakThroughFocus) UNNotificationInterruptionLevelTimeSensitive
                else UNNotificationInterruptionLevelActive
            )
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = delaySeconds,
            repeats = false
        )
        // The same identifier every time: adding it again replaces the pending one.
        val request = UNNotificationRequest.requestWithIdentifier(IDENTIFIER, content, trigger)
        center.addNotificationRequest(request) { error ->
            if (error != null) aapsLogger.error(LTag.BGSOURCE, "DEXCOM_ONEPLUS: cannot arm the missed-reading alarm: $error")
        }
    }

    /** Take the armed alarm down, and the delivered one with it. */
    fun disarm() {
        val ids = listOf(IDENTIFIER)
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    private fun requestAuthorizationOnce() {
        if (authorizationAsked) return
        authorizationAsked = true
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            // Without permission the alarm is armed and never seen, so it is said loudly.
            if (!granted) aapsLogger.error(LTag.BGSOURCE, "DEXCOM_ONEPLUS: notifications not permitted, the missed-reading alarm will not appear")
        }
    }

    internal companion object {

        /**
         * Not under the `aaps-` prefix: the notification platform removes what parses as its own
         * id under that prefix when the person mutes all alarms, and this one is not its own.
         */
        const val IDENTIFIER = "dexcom-oneplus-missed-reading"

        /** iOS refuses a trigger interval that is not positive. */
        private const val MIN_DELAY_SECONDS = 1.0

        /**
         * When the alarm should ring, in seconds from [nowMs], or null when it should not be armed.
         *
         * Null when the person switched the missed-reading alarm off, when the limit is not
         * positive, or when the moment has already passed - then the in-app alarm is the one that
         * speaks, and arming a notification for "now" would only ring at once.
         *
         * @param readingAtMs time of the reading that was just stored.
         * @param staleLimitMinutes `AlertsStaleDataThreshold`, the limit the in-app alarm uses.
         */
        fun delaySeconds(readingAtMs: Long, nowMs: Long, enabled: Boolean, staleLimitMinutes: Int): Double? {
            if (!enabled || staleLimitMinutes <= 0) return null
            val ringAtMs = readingAtMs + staleLimitMinutes * 60_000L
            val delayMs = ringAtMs - nowMs
            if (delayMs <= 0L) return null
            return (delayMs / 1000.0).coerceAtLeast(MIN_DELAY_SECONDS)
        }
    }
}
