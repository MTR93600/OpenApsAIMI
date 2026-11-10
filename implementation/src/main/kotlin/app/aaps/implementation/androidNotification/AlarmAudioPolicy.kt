package app.aaps.implementation.androidNotification

import android.app.NotificationManager
import app.aaps.core.interfaces.notifications.AlarmSoundCategory
import app.aaps.core.interfaces.notifications.NotificationId
import kotlin.math.ceil

/**
 * Rules for an urgent alarm's audio, kept pure so they are unit tested.
 *
 * - Who plays the sound: the looping player (volume ramp, or a chosen melody) or the OS through the
 *   notification channel. A channel sound cannot be lowered or changed, so the player owns it whenever
 *   it can; but when Do Not Disturb blocks alarm audio, only the bypass-DND sound channel is heard.
 * - The alarm volume: an alarm cannot be heard at volume 0, so it is raised to half for the alarm.
 */
internal object AlarmAudioPolicy {

    /** Lowest alarm volume during an urgent alarm, as a share of the maximum. */
    const val MIN_ALARM_VOLUME_FRACTION = 0.5

    /** The alarm stream volume to set for the alarm, `null` when the current one is loud enough. */
    fun raisedAlarmVolume(current: Int, max: Int): Int? {
        if (max <= 0) return null
        val floor = ceil(max * MIN_ALARM_VOLUME_FRACTION).toInt()
        return if (current < floor) floor else null
    }

    /**
     * True when Do Not Disturb would mute alarm audio: total silence, or priority mode without alarms.
     * [alarmsAllowedInPriority] `null` (policy unreadable) counts as blocked: the alarm must be heard.
     */
    fun dndBlocksAlarms(interruptionFilter: Int, alarmsAllowedInPriority: Boolean?): Boolean = when (interruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> alarmsAllowedInPriority != true
        else -> false
    }

    /**
     * The player owns the sound when it ramps or plays a chosen melody, unless the DND override is on
     * and DND blocks alarms: then the bypass-DND sound channel is the only thing that rings.
     */
    fun playerOwnsSound(increaseVolume: Boolean, customMelody: Boolean, overrideDnd: Boolean, dndBlocksAlarms: Boolean): Boolean =
        (increaseVolume || customMelody) && !(overrideDnd && dndBlocksAlarms)

    /** Low-glucose alarms get their own sound choice; every other urgent alarm shares the second one. */
    fun categoryOf(id: NotificationId): AlarmSoundCategory = when (id) {
        NotificationId.BG_HYPO, NotificationId.BG_RAPID_FALL -> AlarmSoundCategory.LOW_GLUCOSE
        else -> AlarmSoundCategory.OTHER
    }
}
