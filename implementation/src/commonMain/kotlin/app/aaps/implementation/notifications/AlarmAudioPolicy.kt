package app.aaps.implementation.notifications

import app.aaps.core.interfaces.notifications.AlarmSound
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
     * both from the glucose alarms the user sets up, and the urgent low alarms of the CGM alarms. The high glucose alarm and the predicted hypo
     * alarm (`HYPO_RISK_ALARM`) use the "other urgent alarms" choice, as on `dev_OAPSAIMI`.
     */
    private val LOW_GLUCOSE_ALARMS: Set<NotificationId> =
        setOf(NotificationId.BG_HYPO, NotificationId.BG_RAPID_FALL, NotificationId.CGM_URGENT_LOW, NotificationId.CGM_URGENT_LOW_SOON)

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

    /**
     * Low-glucose alarms get their own sound choice; every other urgent alarm shares the second one.
     * A Trio [sound] is one the user chose for a single CGM alarm: it is [AlarmSoundCategory.CHOSEN],
     * so no melody replaces it.
     */
    fun categoryOf(id: NotificationId, sound: AlarmSound? = null): AlarmSoundCategory = when {
        sound?.isTrio == true     -> AlarmSoundCategory.CHOSEN
        id in LOW_GLUCOSE_ALARMS  -> AlarmSoundCategory.LOW_GLUCOSE
        else                      -> AlarmSoundCategory.OTHER
    }

    /** The preference that holds the sound choice of [category], `null` for [AlarmSoundCategory.CHOSEN]. */
    fun soundKeyOf(category: AlarmSoundCategory): StringKey? = when (category) {
        AlarmSoundCategory.LOW_GLUCOSE -> StringKey.AlertSoundLowGlucose
        AlarmSoundCategory.OTHER       -> StringKey.AlertSoundOtherAlarms
        AlarmSoundCategory.CHOSEN      -> null
    }

    /** The melody the user chose for [category], `null` for the AAPS sound or a sound chosen for the alarm. */
    fun chosenMelody(preferences: Preferences, category: AlarmSoundCategory): AlarmMelody? =
        soundKeyOf(category)?.let { AlarmMelody.fromChoice(preferences.get(it)) }

    /**
     * True when the looping player must own the sound of [category] whatever else is set: a sound
     * chosen for one alarm cannot be played by a notification channel, which holds only the AAPS
     * sounds.
     */
    fun soundNeedsPlayer(category: AlarmSoundCategory): Boolean = category == AlarmSoundCategory.CHOSEN
}
