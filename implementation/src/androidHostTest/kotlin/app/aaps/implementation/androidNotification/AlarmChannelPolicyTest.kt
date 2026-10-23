package app.aaps.implementation.androidNotification

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
}
