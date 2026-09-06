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
    IgnoredSensorGapAt("calibration_ignored_sensor_gap_at", 0L, exportable = false)
}
