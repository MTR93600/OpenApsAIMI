package app.aaps.core.interfaces.glucose

/**
 * One place that turns a **stored** sensor reading into the value the app really shows.
 *
 * Calibration and smoothing are applied only to the bucketed series in memory
 * ([app.aaps.core.interfaces.aps.AutosensDataStore.getBucketedDataTableCopy]); the stored
 * [app.aaps.core.data.model.GV] keeps the plain sensor value for ever. So everything that reads the
 * database directly - the local glucose alarms, the Nightscout upload, the xDrip broadcast - used to
 * show a different number than the dashboard, the widget, the watch and the loop.
 *
 * This maps a stored reading onto the corrected series, so all of them show one value.
 */
interface GlucoseCorrection {

    /**
     * Corrected value in mg/dL for a reading at [timestamp] whose stored value is [storedMgdl].
     *
     * The corrected series is 5 minutes apart while a sensor may send a value every minute, so a
     * value between two points is interpolated. A reading on the newest corrected point gets that
     * point's value, which is exactly the big number on the dashboard. A reading newer than that point
     * keeps its own stored value plus the newest point's correction, and only when that point is at
     * most 5 minutes older; otherwise there is no answer and the stored value is used.
     *
     * The answer is a **whole mg/dL**, like the stored reading and like the screen, so a caller can
     * hand it straight to Nightscout or to an alarm without rounding again.
     *
     * @return the corrected value, or `null` when none can be worked out: no corrected series yet
     *         (right after app start), a reading older than the series, a reading more than 5 minutes
     *         newer than the series, or a result that failed the
     *         plausibility check. Callers keep [storedMgdl] on `null`.
     */
    fun correctedMgdl(timestamp: Long, storedMgdl: Double): Double?
}
