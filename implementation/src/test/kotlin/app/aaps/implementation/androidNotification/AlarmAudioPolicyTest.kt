package app.aaps.implementation.androidNotification

import android.app.NotificationManager
import app.aaps.core.interfaces.notifications.AlarmSoundCategory
import app.aaps.core.interfaces.notifications.NotificationId
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AlarmAudioPolicyTest {

    @Test
    fun lowAlarmVolumeIsRaisedToHalfAndAnAudibleOneIsLeftAlone() {
        assertThat(AlarmAudioPolicy.raisedAlarmVolume(current = 0, max = 7)).isEqualTo(4)
        assertThat(AlarmAudioPolicy.raisedAlarmVolume(current = 2, max = 15)).isEqualTo(8)
        assertThat(AlarmAudioPolicy.raisedAlarmVolume(current = 8, max = 15)).isNull()
        assertThat(AlarmAudioPolicy.raisedAlarmVolume(current = 3, max = 0)).isNull()
    }

    @Test
    fun dndBlocksAlarmsOnlyInTotalSilenceOrPriorityWithoutAlarms() {
        assertThat(AlarmAudioPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_ALL, alarmsAllowedInPriority = null)).isFalse()
        assertThat(AlarmAudioPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_ALARMS, null)).isFalse()
        assertThat(AlarmAudioPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_NONE, null)).isTrue()
        assertThat(AlarmAudioPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowedInPriority = true)).isFalse()
        assertThat(AlarmAudioPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowedInPriority = false)).isTrue()
        // Policy unreadable: assume the worst, the alarm must be heard.
        assertThat(AlarmAudioPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowedInPriority = null)).isTrue()
    }

    /** The player owns the sound when it ramps or plays a chosen melody, unless DND would mute it. */
    @Test
    fun playerOwnsTheSound() {
        assertThat(AlarmAudioPolicy.playerOwnsSound(increaseVolume = true, customMelody = false, overrideDnd = true, dndBlocksAlarms = false)).isTrue()
        assertThat(AlarmAudioPolicy.playerOwnsSound(increaseVolume = false, customMelody = true, overrideDnd = true, dndBlocksAlarms = false)).isTrue()
        assertThat(AlarmAudioPolicy.playerOwnsSound(increaseVolume = false, customMelody = false, overrideDnd = true, dndBlocksAlarms = false)).isFalse()
        // DND blocks alarm audio: back to the sound channel that rings through DND.
        assertThat(AlarmAudioPolicy.playerOwnsSound(increaseVolume = true, customMelody = true, overrideDnd = true, dndBlocksAlarms = true)).isFalse()
        // No DND override: the player respects DND anyway, nothing to fall back to.
        assertThat(AlarmAudioPolicy.playerOwnsSound(increaseVolume = true, customMelody = false, overrideDnd = false, dndBlocksAlarms = true)).isTrue()
    }

    @Test
    fun lowGlucoseAlarmsHaveTheirOwnSoundCategory() {
        assertThat(AlarmAudioPolicy.categoryOf(NotificationId.BG_HYPO)).isEqualTo(AlarmSoundCategory.LOW_GLUCOSE)
        assertThat(AlarmAudioPolicy.categoryOf(NotificationId.BG_RAPID_FALL)).isEqualTo(AlarmSoundCategory.LOW_GLUCOSE)
        assertThat(AlarmAudioPolicy.categoryOf(NotificationId.PUMP_UNREACHABLE)).isEqualTo(AlarmSoundCategory.OTHER)
    }
}
