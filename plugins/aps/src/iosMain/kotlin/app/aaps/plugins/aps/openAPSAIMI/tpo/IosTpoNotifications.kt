package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.ui.compose.navigation.setNavigationRoute
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The iOS side of [TpoNotifications]: puts a ready-made TPO notification in Notification Center.
 *
 * Like the Android side, this decides nothing. The words arrive already built by
 * [TpoNotificationText] and the preference has already been checked by [TpoOrchestrator], so the
 * two phones say the same sentences in the same order. What is left is the Apple half.
 *
 * ## Channels, and what they become here
 *
 * Android gives the two notifications their own channel: the start one is `IMPORTANCE_DEFAULT` with
 * sound and vibration switched off, the end one is `IMPORTANCE_HIGH` with the default sound. iOS
 * has no channels, but it does not need them, because once the two Android channels are written out
 * as behaviour they differ in exactly one way the user can perceive: **the end notification makes a
 * noise and the start one does not.** So that is what is set here, with
 * `UNNotificationSound.defaultSound()` on the end notification and no sound on the start one.
 *
 * The interruption level is `active` for both, which is the plain "show a banner now" level, and it
 * is the honest match. `timeSensitive` would be wrong even for the end notification: it breaks
 * through a Focus mode, and the Android channel does not set `bypassDnd`, so using it here would
 * make iOS louder than Android rather than equal to it. `critical` needs an Apple entitlement and
 * is reserved for the alarm tier.
 *
 * ## What else is the same
 *
 * Reposting the same identifier replaces the delivered notification instead of stacking a second
 * one, which is what reusing one Android notification id does. Tapping removes it, which is
 * `setAutoCancel(true)` on Android, and it opens the AIMI preference screen: the route travels in
 * `userInfo` through `setNavigationRoute` instead of in an intent extra, under the same key and in
 * the same form, so both phones land on the same screen.
 *
 * Permission is asked for once, lazily, on the first notification - never while the object graph is
 * being built. iOS only shows the system prompt the first time any part of the app asks, so this is
 * not a second prompt; a later call just reads back the answer the user already gave.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosTpoNotifications @Inject constructor(
    private val aapsLogger: AAPSLogger,
) : TpoNotifications {

    /**
     * Resolved on first use, not in the constructor.
     *
     * `currentNotificationCenter()` needs an app bundle and throws
     * `bundleProxyForCurrentProcess is nil` without one, so reaching for it in a field initialiser
     * would make this class impossible to build in a test binary.
     */
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }

    /** Asked once, on first use, for the same reason [center] is resolved lazily. */
    private val authorized: Unit by lazy {
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, error ->
            if (error != null) aapsLogger.error(LTag.NOTIFICATION, "TPO notification permission failed: $error")
            else aapsLogger.debug(LTag.NOTIFICATION, "TPO notification permission granted=$granted")
        }
    }

    override fun show(notification: TpoNotification) {
        authorized
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            // The long form, not the short one: iOS shows the first lines in the banner and the
            // rest when the user pulls it down, which is what BigTextStyle does on Android. The
            // long form starts with the short one, so the collapsed line matches.
            setBody(notification.longText)
            setInterruptionLevel(UNNotificationInterruptionLevelActive)
            if (notification.kind == TpoNotificationKind.SESSION_ENDED) {
                setSound(UNNotificationSound.defaultSound())
            }
            setNavigationRoute(OPENAPS_AIMI_PLUGIN_ROUTE)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = identifierOf(notification.kind),
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(request) { error ->
            if (error != null) aapsLogger.error(LTag.NOTIFICATION, "Cannot post TPO notification: $error")
        }
    }

    override fun cancel(kind: TpoNotificationKind) {
        val ids = listOf(identifierOf(kind))
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
    }

    internal companion object {

        /**
         * Where tapping this notification goes - the same screen the Android notification opens.
         *
         * Written out here because the Android side keeps its own private copy of the string and
         * the shared prefix in `PendingNavigation` is private too, so there is no one constant to
         * share yet. The two must stay equal; the key and the parser already are.
         */

        /**
         * One stable identifier per kind, so a repost replaces the old one and `cancel` finds it.
         *
         * The prefix is this plugin's own, and it must **not** be `aaps-`:
         * `IosSystemNotificationPlatform.cancelAll` - the "mute all alarms" path - removes every
         * delivered notification whose identifier starts with `aaps-` and ends in a number. A TPO
         * notification is not an alarm, so muting alarms must leave it alone.
         */
        fun identifierOf(kind: TpoNotificationKind): String = when (kind) {
            TpoNotificationKind.SESSION_STARTED -> "aimi-tpo-started"
            TpoNotificationKind.SESSION_ENDED   -> "aimi-tpo-ended"
        }
    }
}
