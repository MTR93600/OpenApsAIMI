package app.aaps.pump.medtrum.comm

import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.pump.medtrum.MedtrumStrings
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The person must be told about pump alarms the way Android tells them: same id, level and sound. */
class MedtrumAlarmNotifierTest {

    private val notifications = RecordingNotificationManager()
    private val notifier = MedtrumAlarmNotifier(notifications)

    private fun onState(state: MedtrumPumpState, primed: Boolean = false) =
        notifier.onPumpState(state, primed, MedtrumPumpSyncHistory::alarmText)

    @Test
    fun `an occlusion is a pump error with the alarm sound`() {
        onState(MedtrumPumpState.OCCLUSION)

        val error = notifications.posted.single()
        assertEquals(NotificationId.PUMP_ERROR, error.id)
        assertEquals(AlarmSound.ALARM, error.sound)
    }

    @Test
    fun `an hourly maximum stop is urgent and sounds`() {
        onState(MedtrumPumpState.HOURLY_MAX_SUSPENDED)

        val suspended = notifications.posted.single()
        assertEquals(NotificationId.PUMP_SUSPENDED, suspended.id)
        assertEquals(NotificationLevel.URGENT, suspended.level)
        assertEquals(AlarmSound.ALARM, suspended.sound)
    }

    @Test
    fun `a running patch clears not active and suspended`() {
        onState(MedtrumPumpState.ACTIVE)

        assertTrue(notifications.posted.isEmpty())
        assertEquals(listOf(NotificationId.PATCH_NOT_ACTIVE, NotificationId.PUMP_SUSPENDED), notifications.dismissed)
    }

    @Test
    fun `no patch says so and clears the errors`() {
        onState(MedtrumPumpState.STOPPED)

        assertEquals(NotificationId.PATCH_NOT_ACTIVE, notifications.posted.single().id)
        assertTrue(notifications.dismissed.containsAll(listOf(NotificationId.PUMP_WARNING, NotificationId.PUMP_ERROR, NotificationId.PUMP_SUSPENDED)))
    }

    @Test
    fun `a primed patch that drops back to filled is an urgent error`() {
        onState(MedtrumPumpState.FILLED, primed = true)

        val error = notifications.posted.single()
        assertEquals(NotificationId.PUMP_ERROR, error.id)
        assertEquals(NotificationLevel.URGENT, error.level)
    }

    @Test
    fun `filling a new patch is no error`() {
        onState(MedtrumPumpState.FILLED, primed = false)

        assertTrue(notifications.posted.isEmpty())
    }

    @Test
    fun `a warning is shown only when warnings are on`() {
        notifier.onPumpWarning(AlarmState.PUMP_LOW_RESERVOIR, warningsOn = false, MedtrumPumpSyncHistory::alarmText)
        assertTrue(notifications.posted.isEmpty())

        notifier.onPumpWarning(AlarmState.PUMP_LOW_RESERVOIR, warningsOn = true, MedtrumPumpSyncHistory::alarmText)
        assertEquals(NotificationId.PUMP_WARNING, notifications.posted.single().id)
    }

    // -- Patch expires soon (Android `MedtrumService.checkExpiryWarning`) ---------------------------

    private val patchStart = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun checkExpiry(now: Long, expirationOn: Boolean = true, warningsOn: Boolean = true) =
        notifier.checkExpiryWarning(now, patchStart, thresholdHours = 72, expirationOn = expirationOn, warningsOn = warningsOn)

    @Test
    fun `the expiry warning comes at the threshold hour`() {
        assertTrue(checkExpiry(patchStart + 72 * hour))

        val warning = notifications.posted.single()
        assertEquals(NotificationId.PUMP_WARNING, warning.id)
        assertEquals(MedtrumStrings.alarm_pump_expires_soon, warning.text)
    }

    @Test
    fun `the expiry warning is shown only in the first check window`() {
        assertFalse(checkExpiry(patchStart + 72 * hour - 1))
        assertTrue(checkExpiry(patchStart + 72 * hour + MedtrumAlarmNotifier.CHECK_EXPIRY_WARNING_TIME_MS))
        assertFalse(checkExpiry(patchStart + 72 * hour + MedtrumAlarmNotifier.CHECK_EXPIRY_WARNING_TIME_MS + 1))
        assertEquals(1, notifications.posted.size)
    }

    @Test
    fun `no expiry warning without patch expiry or without pump warnings`() {
        assertFalse(checkExpiry(patchStart + 72 * hour, expirationOn = false))
        assertFalse(checkExpiry(patchStart + 72 * hour, warningsOn = false))
        assertTrue(notifications.posted.isEmpty())
    }

    @Test
    fun `a failed record read is urgent and sounds`() {
        notifier.onRecordSyncFailed()

        val error = notifications.posted.single()
        assertEquals(NotificationId.PUMP_SYNC_ERROR, error.id)
        assertEquals(NotificationLevel.URGENT, error.level)
        assertEquals(AlarmSound.ALARM, error.sound)
    }
}
