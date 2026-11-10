package app.aaps.implementation.androidNotification

/**
 * Which notification channel an urgent alarm uses when the volume ramp is on.
 *
 * On a Pixel 10 Pro XL the hypo alarm was not progressive: the bypass-DND channel carries the sound
 * itself, so the OS played the whole 15 s clip at the alarm volume, and the ramping player only
 * started after it (it waits for the channel clip so the two do not overlap). With "Gradually increase
 * volume" on, the channel must make no sound: it vibrates, then the player rises from silence at once.
 * Without the ramp the callers keep their sound-bearing channels as before.
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
        val channel = if (overrideDnd) AlarmNotificationManager.CHANNEL_RAMP_BYPASS else AlarmNotificationManager.CHANNEL_FULL_SCREEN_SILENT
        return Choice(channelId = channel, waitForChannelSound = false)
    }
}
