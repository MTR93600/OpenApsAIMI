package app.aaps.pump.medtrum.comm

import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
