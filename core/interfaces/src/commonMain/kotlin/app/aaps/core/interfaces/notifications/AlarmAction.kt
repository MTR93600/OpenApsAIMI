package app.aaps.core.interfaces.notifications

/**
 * Action button an alarm carries on its **system** notification (lock screen, notification shade),
 * on top of the in-app notification card.
 *
 * Declared per [NotificationId] and not passed where the alarm is posted, because the button must
 * survive a trip through the operating system: on Android it goes through a `PendingIntent` to a
 * `BroadcastReceiver`, on iOS through a notification category and the notification delegate. The
 * in-app [NotificationAction] holds a lambda, which cannot make that trip. Each platform resolves
 * the label and what the button does.
 */
enum class AlarmAction {

    /** "Hypo treated": the user took carbs, so hold the low-glucose alarm while they work. */
    HYPO_TREATED
}
