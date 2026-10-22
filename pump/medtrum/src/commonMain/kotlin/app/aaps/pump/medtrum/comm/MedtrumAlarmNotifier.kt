package app.aaps.pump.medtrum.comm

import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.pump.medtrum.MedtrumStrings
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

/**
 * What the person is told when the pump state changes: the notifications of the Android
 * `MedtrumService.handlePumpStateUpdate` and `notifyPumpWarning`, with the same ids, levels,
 * sounds and texts.
 *
 * Without it an occlusion, an empty reservoir, an expired patch or an hourly or daily maximum
 * stop was only written to the treatment history on iPhone - no notification, no sound.
 *
 * Only the notifications. What the state change does to the pump history and the patch state
 * stays with the driver that owns them.
 */
class MedtrumAlarmNotifier(private val notifications: NotificationManager) {

    /**
     * Raises or clears the notifications for [state].
     *
     * @param patchPrimed whether the patch had been primed. A drop back to IDLE or FILLED after
     *   priming means the patch restarted by itself and cannot deliver any more.
     * @param alarmText the text of an alarm, as written to the history.
     */
    fun onPumpState(state: MedtrumPumpState, patchPrimed: Boolean, alarmText: (AlarmState) -> String) {
        when (state) {
            MedtrumPumpState.NONE,
            MedtrumPumpState.STOPPED              -> {
                notifications.dismiss(NotificationId.PUMP_WARNING)
                notifications.dismiss(NotificationId.PUMP_ERROR)
                notifications.dismiss(NotificationId.PUMP_SUSPENDED)
                notifications.post(NotificationId.PATCH_NOT_ACTIVE, MedtrumStrings.patch_not_active, level = NotificationLevel.IMPORTANT)
            }

            MedtrumPumpState.IDLE,
            MedtrumPumpState.FILLED               -> {
                notifications.dismiss(NotificationId.PUMP_ERROR)
                notifications.dismiss(NotificationId.PUMP_SUSPENDED)
                if (patchPrimed) {
                    notifications.post(
                        NotificationId.PUMP_ERROR, MedtrumStrings.patch_reset_after_primed_error,
                        level = NotificationLevel.URGENT, sound = AlarmSound.ALARM
                    )
                }
            }

            MedtrumPumpState.PRIMING,
            MedtrumPumpState.PRIMED,
            MedtrumPumpState.EJECTING,
            MedtrumPumpState.EJECTED              -> {
                notifications.dismiss(NotificationId.PUMP_ERROR)
                notifications.dismiss(NotificationId.PUMP_SUSPENDED)
            }

            MedtrumPumpState.ACTIVE,
            MedtrumPumpState.ACTIVE_ALT           -> {
                notifications.dismiss(NotificationId.PATCH_NOT_ACTIVE)
                notifications.dismiss(NotificationId.PUMP_SUSPENDED)
            }

            MedtrumPumpState.LOW_BG_SUSPENDED,
            MedtrumPumpState.LOW_BG_SUSPENDED2,
            MedtrumPumpState.AUTO_SUSPENDED,
            MedtrumPumpState.SUSPENDED,
            MedtrumPumpState.PAUSED               ->
                notifications.post(NotificationId.PUMP_SUSPENDED, MedtrumStrings.pump_is_suspended)

            MedtrumPumpState.HOURLY_MAX_SUSPENDED ->
                notifications.post(
                    NotificationId.PUMP_SUSPENDED, MedtrumStrings.pump_is_suspended_hour_max,
                    level = NotificationLevel.URGENT, sound = AlarmSound.ALARM
                )

            MedtrumPumpState.DAILY_MAX_SUSPENDED  ->
                notifications.post(
                    NotificationId.PUMP_SUSPENDED, MedtrumStrings.pump_is_suspended_day_max,
                    level = NotificationLevel.URGENT, sound = AlarmSound.ALARM
                )

            MedtrumPumpState.OCCLUSION,
            MedtrumPumpState.EXPIRED,
            MedtrumPumpState.RESERVOIR_EMPTY,
            MedtrumPumpState.PATCH_FAULT,
            MedtrumPumpState.PATCH_FAULT2,
            MedtrumPumpState.BASE_FAULT,
            MedtrumPumpState.BATTERY_OUT,
            MedtrumPumpState.NO_CALIBRATION       -> {
                notifications.dismiss(NotificationId.PATCH_NOT_ACTIVE)
                notifications.dismiss(NotificationId.PUMP_SUSPENDED)
                val alarm = MedtrumPumpSyncHistory.alarmFor(state)?.let(alarmText).toString()
                notifications.post(NotificationId.PUMP_ERROR, MedtrumStrings.pump_error.withArgs(alarm), sound = AlarmSound.ALARM)
            }
        }
    }

    /**
     * A warning from the pump: low reservoir, low battery, patch expires soon. Shown only when the
     * person keeps pump warnings on, as on Android.
     */
    fun onPumpWarning(alarm: AlarmState, warningsOn: Boolean, alarmText: (AlarmState) -> String) {
        if (!warningsOn || alarm == AlarmState.NONE) return
        notifications.post(NotificationId.PUMP_WARNING, MedtrumStrings.pump_warning.withArgs(alarmText(alarm)))
    }

    /** A history record could not be read, as on Android: the first failure is told, loudly. */
    fun onRecordSyncFailed() {
        notifications.post(NotificationId.PUMP_SYNC_ERROR, MedtrumStrings.pump_sync_error, level = NotificationLevel.URGENT, sound = AlarmSound.ALARM)
    }
}
