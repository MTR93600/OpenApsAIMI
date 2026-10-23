package app.aaps.implementation.notifications

import app.aaps.core.interfaces.notifications.AlarmSoundCategory
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.keys.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In commonTest so the rules are checked on the JVM and on the iOS simulator. */
class AlarmAudioPolicyTest {

    @Test
    fun lowAlarmVolumeIsRaisedToHalfAndAnAudibleOneIsLeftAlone() {
        assertEquals(4, AlarmAudioPolicy.raisedAlarmVolume(current = 0, max = 7))
        assertEquals(8, AlarmAudioPolicy.raisedAlarmVolume(current = 2, max = 15))
        assertNull(AlarmAudioPolicy.raisedAlarmVolume(current = 8, max = 15))
        assertNull(AlarmAudioPolicy.raisedAlarmVolume(current = 3, max = 0))
    }

    /** The player owns the sound when it ramps or plays a chosen melody, unless DND would mute it. */
    @Test
    fun playerOwnsTheSound() {
        assertTrue(AlarmAudioPolicy.playerOwnsSound(increaseVolume = true, customMelody = false, overrideDnd = true, dndBlocksAlarms = false))
        assertTrue(AlarmAudioPolicy.playerOwnsSound(increaseVolume = false, customMelody = true, overrideDnd = true, dndBlocksAlarms = false))
        assertFalse(AlarmAudioPolicy.playerOwnsSound(increaseVolume = false, customMelody = false, overrideDnd = true, dndBlocksAlarms = false))
        // DND blocks alarm audio: back to the sound channel that rings through DND.
        assertFalse(AlarmAudioPolicy.playerOwnsSound(increaseVolume = true, customMelody = true, overrideDnd = true, dndBlocksAlarms = true))
        // No DND override: the player respects DND anyway, nothing to fall back to.
        assertTrue(AlarmAudioPolicy.playerOwnsSound(increaseVolume = true, customMelody = false, overrideDnd = false, dndBlocksAlarms = true))
    }

    /**
     * The low-glucose alarms of `dev_OAPSAIMI` (`BG_HYPO`, `BG_RAPID_FALL`) are not on this branch yet,
     * so today every alarm uses the "other urgent alarms" choice - the predicted hypo alarm too, as on
     * `dev_OAPSAIMI`.
     */
    @Test
    fun alarmsOnThisBranchUseTheOtherAlarmsSoundChoice() {
        assertEquals(AlarmSoundCategory.OTHER, AlarmAudioPolicy.categoryOf(NotificationId.PUMP_UNREACHABLE))
        assertEquals(AlarmSoundCategory.OTHER, AlarmAudioPolicy.categoryOf(NotificationId.HYPO_RISK_ALARM))
    }

    @Test
    fun eachCategoryReadsItsOwnPreference() {
        assertEquals(StringKey.AlertSoundLowGlucose, AlarmAudioPolicy.soundKeyOf(AlarmSoundCategory.LOW_GLUCOSE))
        assertEquals(StringKey.AlertSoundOtherAlarms, AlarmAudioPolicy.soundKeyOf(AlarmSoundCategory.OTHER))
    }

    /** Every melody can be chosen in the settings list, and the list offers nothing that cannot play. */
    @Test
    fun settingsChoicesMatchTheMelodies() {
        for (key in listOf(StringKey.AlertSoundLowGlucose, StringKey.AlertSoundOtherAlarms)) {
            val choices = key.entries.keys
            assertEquals(AlarmMelody.DEFAULT_CHOICE, key.defaultValue)
            assertEquals(setOf(AlarmMelody.DEFAULT_CHOICE) + AlarmMelody.entries.map { it.id }, choices)
        }
    }
}
