package app.aaps.implementation.androidNotification

import android.app.NotificationManager

/**
 * The Android-only half of the alarm audio rules (the rest is the shared
 * [app.aaps.implementation.notifications.AlarmAudioPolicy]): reading Do Not Disturb. Kept pure so it
 * is unit tested.
 */
internal object AlarmDndPolicy {

    /**
     * True when Do Not Disturb would mute alarm audio: total silence, or priority mode without alarms.
     * [alarmsAllowedInPriority] `null` (policy unreadable) counts as blocked: the alarm must be heard.
     */
    fun dndBlocksAlarms(interruptionFilter: Int, alarmsAllowedInPriority: Boolean?): Boolean = when (interruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE     -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> alarmsAllowedInPriority != true
        else                                             -> false
    }
}
