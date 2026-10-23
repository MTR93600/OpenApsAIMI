package app.aaps.implementation.androidNotification

import app.aaps.implementation.androidNotification.AlarmChannelPolicy.InternalAlarmChannel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * A hypo alarm on a Pixel 10 Pro XL was not progressive: the bypass-DND channel played the whole
 * 15 s clip at the alarm volume first, and the ramping player only started after it. When the player
 * owns the sound (ramp or chosen melody), the channel must stay silent (vibration only) and the
 * player must start at once.
 */
class AlarmChannelPolicyTest {

    @Test
    fun playerOwnedSoundWithDndOverrideUsesTheSilentBypassChannelAndStartsAtOnce() {
        val c = AlarmChannelPolicy.forPlayerOwnedSound(playerOwnsSound = true, overrideDnd = true)!!
        assertThat(c.channelId).isEqualTo(AlarmNotificationManager.CHANNEL_RAMP_BYPASS)
        assertThat(c.waitForChannelSound).isFalse()
    }

    @Test
    fun playerOwnedSoundWithoutDndOverrideUsesTheSilentUrgentChannel() {
        val c = AlarmChannelPolicy.forPlayerOwnedSound(playerOwnsSound = true, overrideDnd = false)!!
        assertThat(c.channelId).isEqualTo(AlarmNotificationManager.CHANNEL_SILENT_URGENT)
        assertThat(c.waitForChannelSound).isFalse()
    }

    /** The OS plays the sound (no ramp, no melody, or DND blocks alarms): the caller keeps its sound-bearing channel. */
    @Test
    fun soundChannelWhenTheOsPlaysTheSound() {
        assertThat(AlarmChannelPolicy.forPlayerOwnedSound(playerOwnsSound = false, overrideDnd = true)).isNull()
        assertThat(AlarmChannelPolicy.forPlayerOwnedSound(playerOwnsSound = false, overrideDnd = false)).isNull()
    }

    // In-app alarms (low glucose, pump unreachable...): exactly one of the channel and the player sounds.

    private fun internal(overrideDnd: Boolean = true, playerOwnsSound: Boolean = false, dndBlocksAlarms: Boolean = false, playerAlreadySounding: Boolean = false) =
        AlarmChannelPolicy.forInternalAlarm(overrideDnd, playerOwnsSound, dndBlocksAlarms, playerAlreadySounding)

    @Test
    fun withoutTheDndOverrideTheAlarmIsPostedSilently() {
        assertThat(internal(overrideDnd = false)).isEqualTo(InternalAlarmChannel.SILENT)
        assertThat(internal(overrideDnd = false, dndBlocksAlarms = true)).isEqualTo(InternalAlarmChannel.SILENT)
    }

    @Test
    fun whenThePlayerOwnsTheSoundTheBypassChannelOnlyVibrates() {
        assertThat(internal(playerOwnsSound = true)).isEqualTo(InternalAlarmChannel.BYPASS_VIBRATE)
    }

    @Test
    fun withoutRampOrMelodyTheChannelPlaysTheClip() {
        assertThat(internal()).isEqualTo(InternalAlarmChannel.BYPASS_SOUND)
    }

    /** The same alarm posted again while its sound loops: a second clip on top would be two sounds. */
    @Test
    fun aRepostWhileThePlayerSoundsDoesNotAddAClip() {
        assertThat(internal(playerAlreadySounding = true)).isEqualTo(InternalAlarmChannel.BYPASS_VIBRATE)
    }

    /** Do Not Disturb mutes the player, so the clip is the only thing heard, also on a repost. */
    @Test
    fun whenDndBlocksAlarmsTheClipAlwaysRings() {
        assertThat(internal(dndBlocksAlarms = true)).isEqualTo(InternalAlarmChannel.BYPASS_SOUND)
        assertThat(internal(dndBlocksAlarms = true, playerAlreadySounding = true)).isEqualTo(InternalAlarmChannel.BYPASS_SOUND)
    }
}
