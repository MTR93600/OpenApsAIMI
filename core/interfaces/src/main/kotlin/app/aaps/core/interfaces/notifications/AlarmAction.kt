package app.aaps.core.interfaces.notifications

/**
 * Action button an alarm carries on its **Android** notification (lock screen / shade), on top of
 * the in-app notification card.
 *
 * Declared per [NotificationId] rather than passed at the post site, because the button must survive
 * a trip through a `PendingIntent`: the in-app [NotificationAction] holds a lambda, which cannot
 * cross into a `BroadcastReceiver`. The label and the receiver are resolved in the UI layer.
 */
enum class AlarmAction {

    /** "Hypo treated" — the user took carbs, so hold the low-glucose alarm while they work. */
    HYPO_TREATED
}
