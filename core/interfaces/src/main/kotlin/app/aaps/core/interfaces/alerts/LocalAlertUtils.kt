package app.aaps.core.interfaces.alerts

interface LocalAlertUtils {

    /**
     * Check for unreachable pump (there was no connection with pump for some time).
     * Raise alarm if needed.
     * Overview notification with sound, Therapy event, SMS
     */
    fun checkPumpUnreachableAlarm(lastConnection: Long, isStatusOutdated: Boolean, isDisconnected: Boolean)

    /**
     * Preset next alarm at least 5 min after start of app
     * Call only at startup!
     */
    fun preSnoozeAlarms()

    /**
     * Shortens alarm times in case of setting changes or future data
     */
    fun shortenSnoozeInterval()

    /**
     * Report pump status has been read.
     * Shifts threshold for next alarm to now + preset_interval
     */
    suspend fun reportPumpStatusRead()

    /**
     * Check for missing BGs.
     * Raise alarm if needed.
     * Overview notification with sound, Therapy event
     */
    suspend fun checkStaleBGAlert()

    /**
     * Check the current glucose against the user-configured value/rate alarms
     * (low / high / rapid fall) and raise or dismiss notifications accordingly.
     *
     * Source-agnostic (reads the last stored glucose, so it works for any CGM source) and
     * notification-only — it never affects dosing or the loop. Skips silently when data is stale
     * (the stale-data alarm owns that case).
     */
    suspend fun checkGlucoseAlerts()

    /**
     * The user reported treating the current hypo (carbs taken): clear the low-glucose alert and
     * hold it for about the time carbs need to work, instead of the shorter automatic re-alarm.
     *
     * Silences only — the alarm comes back by itself if glucose is still low when the hold ends,
     * and [checkGlucoseAlerts] clears the hold as soon as glucose has recovered.
     */
    fun snoozeHypoAfterTreatment()
}