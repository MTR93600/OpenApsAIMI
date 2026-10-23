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
 * What the person is told when the pump state changes: the notifications of
 * `MedtrumService.handlePumpStateUpdate`, `notifyPumpWarning`, `checkExpiryWarning` and
 * `syncRecords`, with the same ids, levels, sounds and texts. Android and iPhone both use this
 * class, so the two cannot drift apart.
 *
 * Without it an occlusion, an empty reservoir, an expired patch or an hourly or daily maximum
 * stop was only written to the treatment history on iPhone - no notification, no sound.
 *
 * Only the notifications. What the state change does to the pump history and the patch state
 * stays with the driver that owns them.
 */
class MedtrumAlarmNotifier(private val notifications: NotificationManager) {

    companion object {

        /**
         * Android `MedtrumService.CHECK_EXPIRY_WARNING_TIME_MS`: how often the expiry warning is
         * checked, and how long after the warning time it is still shown.
         */
        const val CHECK_EXPIRY_WARNING_TIME_MS: Long = 5 * 60 * 1000L

        private const val HOUR_MS: Long = 60 * 60 * 1000L
    }

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

    /**
     * Android `MedtrumService.checkExpiryWarning`, run every [CHECK_EXPIRY_WARNING_TIME_MS]: when
     * the patch has run for [thresholdHours], say that it expires soon. The warning is shown only in
     * the first [CHECK_EXPIRY_WARNING_TIME_MS] after that time, so a check every
     * [CHECK_EXPIRY_WARNING_TIME_MS] shows it once.
     *
     * @param expirationOn the patch expiry setting (`desiredPatchExpiration`)
     * @param warningsOn the pump warning setting (`desiredPumpWarning`)
     * @return true when the warning was shown. The caller then writes the announcement to the history.
     */
    fun checkExpiryWarning(now: Long, patchStartTime: Long, thresholdHours: Int, expirationOn: Boolean, warningsOn: Boolean): Boolean {
        if (!expirationOn || !warningsOn) return false
        val warningAt = patchStartTime + thresholdHours * HOUR_MS
        if (now < warningAt || now > warningAt + CHECK_EXPIRY_WARNING_TIME_MS) return false
        // Keep the id's own NORMAL level, as Android does.
        notifications.post(NotificationId.PUMP_WARNING, MedtrumStrings.alarm_pump_expires_soon)
        return true
    }

    /** A history record could not be read, as on Android: the first failure is told, loudly. */
    fun onRecordSyncFailed() {
        notifications.post(NotificationId.PUMP_SYNC_ERROR, MedtrumStrings.pump_sync_error, level = NotificationLevel.URGENT, sound = AlarmSound.ALARM)
    }
}
