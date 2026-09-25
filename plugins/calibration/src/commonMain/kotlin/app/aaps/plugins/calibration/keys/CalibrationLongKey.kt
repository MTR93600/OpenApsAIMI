package app.aaps.plugins.calibration.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

/**
 * Ref `plugins/calibration/.../keys/CalibrationLongKey.kt` @ `6598201d`.
 *
 * `exportable = false` matches the ref. [app.aaps.implementation.maintenance.LocalImportExportPrefs]
 * and the Android exporter both keep a key only when `Preferences.isExportableKey` is true, which
 * requires this flag. `LinearCalibrationPlugin` registers `CalibrationLongKey.entries` once;
 * a new entry is covered by that call. There is no second list.
 */
enum class CalibrationLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    /**
     * Midpoint of a glucose gap the user said is not a new sensor.
     * Kept across restarts so the same break is not asked again.
     *
     * Ref L11–15 @ `6598201d` (`78ecf14c88`). Written by the ignore action, plugin L416.
     */
    IgnoredSensorGapAt("calibration_ignored_sensor_gap_at", 0L, exportable = false),

    /**
     * Entries older than this are left out of the fit — see `Calibration.ignoreEntriesBefore`.
     * Written when a pre-soak sensor is promoted, whose session is dated before the swap.
     */
    EntriesValidFrom("calibration_entries_valid_from", 0L, exportable = false)
}
