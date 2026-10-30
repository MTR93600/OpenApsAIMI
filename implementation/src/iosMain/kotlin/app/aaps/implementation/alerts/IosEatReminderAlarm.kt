package app.aaps.implementation.alerts

import app.aaps.core.interfaces.alerts.EatReminder
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.notifications.IosNotificationDelegate
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.CoreUiStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionCustomDismissAction
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationDismissActionIdentifier
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The "time to eat" alarm as a scheduled local notification, like `IosReminderScheduler`.
 *
 * The system holds the notification, so it rings at the time even when the app was terminated since. It has
 * one fixed identifier: scheduling again replaces the pending one, and [cancel] takes it back.
 *
 * In this fork the iOS app is a master, so it owns the timer and sets this alarm. The notification carries the
 * same two buttons as Android's alarm, Stop and +5 min. Opening it or swiping it away is the answer to the
 * alarm, like stopping Android's alarm screen: the timer has rung and ends ([EatReminder.onRang], then
 * [EatReminder.stop], which ends only this timer and its own alarm). Without that the bar kept showing a timer
 * that had already rung.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosEatReminderAlarm(
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    // A function: the timer itself owns this alarm, so taking it directly would be a cycle.
    private val eatReminder: () -> EatReminder,
) : EatReminderAlarm {

    init {
        IosNotificationDelegate.register(setOf(category())) { actionId, notificationId ->
            if (notificationId != IDENTIFIER) return@register false
            when (actionId) {
                ACTION_STOP                          -> eatReminder().stop()
                ACTION_EXTEND                        -> eatReminder().extend()
                UNNotificationDefaultActionIdentifier,
                UNNotificationDismissActionIdentifier -> eatReminder().run {
                    onRang()
                    stop()
                }

                else                                 -> return@register false
            }
            true
        }
    }

    // Lazy for the same reason as in IosReminderScheduler: it needs an app bundle, which a test binary has not.
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }
    private var authorizationAsked = false

    override fun schedule(startedAt: Long, endsAt: Long): Boolean {
        val seconds = (endsAt - dateUtil.now()) / 1000.0
        // iOS refuses a non-positive interval.
        if (seconds <= 0) {
            aapsLogger.error(LTag.AUTOMATION, "Eat reminder end is not in the future")
            return false
        }
        requestAuthorizationOnce()
        val content = UNMutableNotificationContent().apply {
            setBody(rh.gs(CoreUiStrings.eat_reminder_title))
            setSound(UNNotificationSound.defaultSound())
            // Breaks through Focus, which is what an alarm the user set is for.
            setInterruptionLevel(UNNotificationInterruptionLevelTimeSensitive)
            setCategoryIdentifier(CATEGORY)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(timeInterval = seconds, repeats = false)
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(IDENTIFIER, content, trigger)) { error ->
            if (error != null) aapsLogger.error(LTag.AUTOMATION, "Cannot schedule eat reminder: $error")
        }
        return true
    }

    override fun cancel() {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(IDENTIFIER))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(IDENTIFIER))
    }

    private fun requestAuthorizationOnce() {
        if (authorizationAsked) return
        authorizationAsked = true
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
            if (!granted) aapsLogger.error(LTag.AUTOMATION, "Notifications not permitted, the eat reminder will not appear")
        }
    }

    /**
     * Stop and +5 min. No button option, so pressing one does not open the app: iOS runs it in the background.
     * `customDismissAction` is what reports a swipe away.
     */
    private fun category(): UNNotificationCategory =
        UNNotificationCategory.categoryWithIdentifier(
            identifier = CATEGORY,
            actions = listOf(
                UNNotificationAction.actionWithIdentifier(identifier = ACTION_STOP, title = rh.gs(CoreUiStrings.stop), options = 0u),
                UNNotificationAction.actionWithIdentifier(
                    identifier = ACTION_EXTEND,
                    title = rh.gs(CoreUiStrings.eat_reminder_extend, EatReminder.EXTEND_MINUTES),
                    options = 0u
                ),
            ),
            intentIdentifiers = emptyList<Any>(),
            options = UNNotificationCategoryOptionCustomDismissAction
        )

    internal companion object {

        const val IDENTIFIER = "aaps-eat-reminder"
        const val CATEGORY = "aaps-eat-reminder-category"
        const val ACTION_STOP = "aaps-eat-reminder-stop"
        const val ACTION_EXTEND = "aaps-eat-reminder-extend"
    }
}
