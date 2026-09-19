package app.aaps.plugins.calibration.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class CalibrationLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    /**
     * Midpoint of a glucose gap the user said is not a new sensor.
     * Kept across restarts so the same break is not asked again.
     */
    IgnoredSensorGapAt("calibration_ignored_sensor_gap_at", 0L, exportable = false),

    /**
     * Entries older than this are left out of the fit — see `Calibration.ignoreEntriesBefore`.
     * Written when a pre-soak sensor is promoted, whose session is dated before the swap.
     */
    EntriesValidFrom("calibration_entries_valid_from", 0L, exportable = false)
}
