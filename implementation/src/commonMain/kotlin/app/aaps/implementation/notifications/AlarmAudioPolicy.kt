package app.aaps.implementation.notifications

import app.aaps.core.interfaces.notifications.AlarmSoundCategory
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlin.math.ceil

/**
 * Rules for an urgent alarm's audio, kept pure so they are unit tested on every platform.
 *
 * - Who plays the sound: the looping player (volume ramp, or a chosen melody) or the OS through the
 *   notification channel. A channel sound cannot be lowered or changed, so the player owns it whenever
 *   it can; but when Do Not Disturb blocks alarm audio, only the bypass-DND sound channel is heard.
 *   (That part is Android only. iOS has no channel sound, its player always owns the alarm audio.)
 * - The alarm volume: an alarm cannot be heard at volume 0, so it is raised to half for the alarm.
 *   (Android only too: iOS gives an app no way to set the system volume.)
 * - Which sound choice applies: low-glucose alarms have their own, every other alarm shares one.
 */
object AlarmAudioPolicy {

    /** Lowest alarm volume during an urgent alarm, as a share of the maximum. */
    const val MIN_ALARM_VOLUME_FRACTION = 0.5

    /**
     * Alarms that use the low-glucose sound choice: the low glucose alarm and the rapid fall alarm,
     * both from the glucose alarms the user sets up. The high glucose alarm and the predicted hypo
     * alarm (`HYPO_RISK_ALARM`) use the "other urgent alarms" choice, as on `dev_OAPSAIMI`.
     */
    private val LOW_GLUCOSE_ALARMS: Set<NotificationId> = setOf(NotificationId.BG_HYPO, NotificationId.BG_RAPID_FALL)

    /** The alarm stream volume to set for the alarm, `null` when the current one is loud enough. */
    fun raisedAlarmVolume(current: Int, max: Int): Int? {
        if (max <= 0) return null
        val floor = ceil(max * MIN_ALARM_VOLUME_FRACTION).toInt()
        return if (current < floor) floor else null
    }

    /**
     * The player owns the sound when it ramps or plays a chosen melody, unless the DND override is on
     * and DND blocks alarms: then the bypass-DND sound channel is the only thing that rings.
     */
    fun playerOwnsSound(increaseVolume: Boolean, customMelody: Boolean, overrideDnd: Boolean, dndBlocksAlarms: Boolean): Boolean =
        (increaseVolume || customMelody) && !(overrideDnd && dndBlocksAlarms)

    /** Low-glucose alarms get their own sound choice; every other urgent alarm shares the second one. */
    fun categoryOf(id: NotificationId): AlarmSoundCategory =
        if (id in LOW_GLUCOSE_ALARMS) AlarmSoundCategory.LOW_GLUCOSE else AlarmSoundCategory.OTHER

    /** The preference that holds the sound choice of [category]. */
    fun soundKeyOf(category: AlarmSoundCategory): StringKey =
        if (category == AlarmSoundCategory.LOW_GLUCOSE) StringKey.AlertSoundLowGlucose else StringKey.AlertSoundOtherAlarms

    /** The melody the user chose for [category], `null` for the AAPS sound. */
    fun chosenMelody(preferences: Preferences, category: AlarmSoundCategory): AlarmMelody? =
        AlarmMelody.fromChoice(preferences.get(soundKeyOf(category)))
}
