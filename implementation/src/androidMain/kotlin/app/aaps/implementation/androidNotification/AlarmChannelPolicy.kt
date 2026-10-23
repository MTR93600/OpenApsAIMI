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
}
