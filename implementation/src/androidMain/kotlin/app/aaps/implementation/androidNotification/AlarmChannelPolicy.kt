package app.aaps.implementation.androidNotification

/**
 * Which notification channel the full-screen alarm uses when the looping player owns the sound
 * (volume ramp, or a chosen soft melody).
 *
 * On a Pixel 10 Pro XL the hypo alarm was not progressive: the bypass-DND channel carries the sound
 * itself, so the OS played the whole 15 s clip at the alarm volume, and the ramping player only
 * started after it (it waits for the channel clip so the two do not overlap). When the player owns the
 * sound, the channel must make no sound: it vibrates, then the player starts at once. Otherwise the
 * caller keeps its sound-bearing channel as before.
 */
internal object AlarmChannelPolicy {

    data class Choice(
        val channelId: String,
        /** True when the channel plays a clip and the player must wait for its end. */
        val waitForChannelSound: Boolean,
    )

    /** The channel when the player owns the sound, `null` when the caller keeps its own. */
    fun forPlayerOwnedSound(playerOwnsSound: Boolean, overrideDnd: Boolean): Choice? {
        if (!playerOwnsSound) return null
        val channel = if (overrideDnd) AlarmNotificationManager.CHANNEL_RAMP_BYPASS else AlarmNotificationManager.CHANNEL_SILENT_URGENT
        return Choice(channelId = channel, waitForChannelSound = false)
    }

    /** How an in-app alarm (low glucose, pump unreachable...) is posted. */
    enum class InternalAlarmChannel {

        /** Silent channel that respects Do Not Disturb. The player owns the sound. */
        SILENT,

        /** Silent channel that shows and vibrates through Do Not Disturb. The player owns the sound. */
        BYPASS_VIBRATE,

        /** Sound channel that rings through Do Not Disturb. The player waits for the clip to end. */
        BYPASS_SOUND
    }

    /**
     * The channel of an in-app alarm that carries a sound.
     *
     * Rule: exactly one of the channel and the player makes the sound at a time.
     * - Override off: the silent channel; the player owns the sound and respects Do Not Disturb.
     * - Player owns the sound (ramp or melody, and Do Not Disturb does not block alarms): the silent
     *   bypass channel.
     * - Do Not Disturb blocks alarms: the sound channel, because the player is muted then and the
     *   channel clip is the only thing the person hears. This is also true when the player is already
     *   sounding this alarm, since it cannot be heard.
     * - The player already sounds an alarm (this one posted again, or an earlier one): the silent
     *   bypass channel. The player keeps playing (or moves to the new alarm at once), and a clip on top
     *   of it would be two sounds at once.
     * - Otherwise: the sound channel plays the clip, and the player starts after it.
     */
    fun forInternalAlarm(overrideDnd: Boolean, playerOwnsSound: Boolean, dndBlocksAlarms: Boolean, playerAlreadySounding: Boolean): InternalAlarmChannel = when {
        !overrideDnd          -> InternalAlarmChannel.SILENT
        playerOwnsSound       -> InternalAlarmChannel.BYPASS_VIBRATE
        dndBlocksAlarms       -> InternalAlarmChannel.BYPASS_SOUND
        playerAlreadySounding -> InternalAlarmChannel.BYPASS_VIBRATE
        else                  -> InternalAlarmChannel.BYPASS_SOUND
    }
}
