package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.ui.compose.navigation.PendingNavigation

/**
 * Puts a ready-made TPO notification in the system tray, and takes it away again.
 *
 * This is only the hand-off to the operating system. Everything else is shared:
 * [TpoOrchestrator] decides **when** to tell the user (the `OApsAIMITpoNotifyOnApply` preference and
 * the session state), and [TpoNotificationText] decides **what** it says. So both phones show the
 * same words, in the same order, without either implementation repeating a sentence.
 *
 * Implementations: `TpoNotificationManager` (Android) and `IosTpoNotifications` (iOS), both bound
 * with `@ContributesBinding(AppScope::class)`.
 */
interface TpoNotifications {

    /** Show [notification], replacing any earlier one of the same [TpoNotification.kind]. */
    fun show(notification: TpoNotification)

    /** Take the notification of this [kind] out of the tray. Does nothing when it is not there. */
    fun cancel(kind: TpoNotificationKind)
}

/**
 * Which of the two TPO notifications this is, and how loud it should be.
 *
 * The loudness belongs here rather than in either implementation, because it is a product decision
 * and the two platforms must make the same one. How it is carried out is platform work: Android uses
 * a notification channel, iOS sets a sound on the request.
 */
enum class TpoNotificationKind {

    /**
     * A session has just been applied. Quiet: the loop changed its own settings for 45 minutes and
     * is only reporting it, so there is nothing for the user to do. No sound, no vibration.
     */
    SESSION_STARTED,

    /**
     * The session is over and the user's own settings are back. This one alerts: the loop is
     * behaving differently from a minute ago, and the user may want to know that without opening
     * the phone.
     */
    SESSION_ENDED,
}

/**
 * One TPO notification, in the user's language, ready to show.
 *
 * [text] is the one line that fits in a collapsed notification. [longText] is the whole thing, and
 * it starts with [text], so a platform that shows only the beginning of the long form still shows
 * the same first line.
 */
data class TpoNotification(
    val kind: TpoNotificationKind,
    val title: String,
    val text: String,
    val longText: String,
)

/** Why an active TPO session stopped. */
enum class TpoEndReason {
    EXPIRED,
    MANUAL_REVERT,
    SUPERSEDED,
}

/**
 * Where tapping a TPO notification goes: the AIMI plugin's own settings.
 *
 * Shared between the two posters on purpose. It used to be a private constant in each of them, so
 * the same string was written twice and one could have been corrected without the other. The key is
 * the plugin's class simple name, because that is what the destination matches on.
 */
val OPENAPS_AIMI_PLUGIN_ROUTE: String = PendingNavigation.pluginPreferencesRoute("OpenAPSAIMIPlugin")
