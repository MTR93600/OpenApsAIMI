package app.aaps.implementation.notifications

import app.aaps.core.interfaces.notifications.AlarmSound
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

    /** The low glucose and rapid fall alarms use the low-glucose sound choice. */
    @Test
    fun lowAndFallingGlucoseAlarmsUseTheLowGlucoseSoundChoice() {
        assertEquals(AlarmSoundCategory.LOW_GLUCOSE, AlarmAudioPolicy.categoryOf(NotificationId.BG_HYPO))
        assertEquals(AlarmSoundCategory.LOW_GLUCOSE, AlarmAudioPolicy.categoryOf(NotificationId.BG_RAPID_FALL))
    }

    /** Every other alarm uses the "other urgent alarms" choice - the predicted hypo alarm too, as on `dev_OAPSAIMI`. */
    @Test
    fun otherAlarmsUseTheOtherAlarmsSoundChoice() {
        assertEquals(AlarmSoundCategory.OTHER, AlarmAudioPolicy.categoryOf(NotificationId.BG_HYPER))
        assertEquals(AlarmSoundCategory.OTHER, AlarmAudioPolicy.categoryOf(NotificationId.PUMP_UNREACHABLE))
        assertEquals(AlarmSoundCategory.OTHER, AlarmAudioPolicy.categoryOf(NotificationId.HYPO_RISK_ALARM))
    }

    @Test
    fun eachCategoryReadsItsOwnPreference() {
        assertEquals(StringKey.AlertSoundLowGlucose, AlarmAudioPolicy.soundKeyOf(AlarmSoundCategory.LOW_GLUCOSE))
        assertEquals(StringKey.AlertSoundOtherAlarms, AlarmAudioPolicy.soundKeyOf(AlarmSoundCategory.OTHER))
        assertNull(AlarmAudioPolicy.soundKeyOf(AlarmSoundCategory.CHOSEN))
    }

    /** A Trio sound the user chose for one CGM alarm wins over the melody choice, and only the player can play it. */
    @Test
    fun aChosenTrioSoundIsNeverReplacedByAMelody() {
        assertEquals(AlarmSoundCategory.CHOSEN, AlarmAudioPolicy.categoryOf(NotificationId.BG_HYPO, AlarmSound.TRIO_TONE_HARP))
        assertEquals(AlarmSoundCategory.LOW_GLUCOSE, AlarmAudioPolicy.categoryOf(NotificationId.CGM_URGENT_LOW, AlarmSound.URGENT_ALARM))
        assertTrue(AlarmAudioPolicy.soundNeedsPlayer(AlarmSoundCategory.CHOSEN))
        assertFalse(AlarmAudioPolicy.soundNeedsPlayer(AlarmSoundCategory.OTHER))
    }

    /** Every Trio sound has its file, and the CGM alarm sound lists offer only sounds that exist. */
    @Test
    fun theTrioSoundsAreAllKnown() {
        val trio = AlarmSound.entries.filter { it.isTrio }
        assertEquals(44, trio.size)
        assertTrue(trio.all { it.fileExtension == "m4a" })
        val files = trio.map { it.fileName }.toSet()
        val cgmSoundKeys = StringKey.entries.filter { it.key.startsWith("alert_cgm_") && it.key.endsWith("_sound") }
        assertEquals(10, cgmSoundKeys.size)
        cgmSoundKeys.forEach { key ->
            key.entries.keys.filter { it.startsWith("trio_") }.forEach { assertTrue(it in files, "${key.key}: $it") }
        }
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
