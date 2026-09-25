package app.aaps.plugins.calibration.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

/**
 * Ref `plugins/calibration/.../keys/CalibrationLongKey.kt` @ `6598201d`.
 *
 * Only [EntriesValidFrom] is ported. `IgnoredSensorGapAt` is the gap-notification memory, not the
 * fit selection, and study gap detection does not read it.
 *
 * `exportable = false` matches the ref. [app.aaps.implementation.maintenance.LocalImportExportPrefs]
 * and the Android exporter both keep a key only when `Preferences.isExportableKey` is true, which
 * requires this flag. Registering the entry is what puts it in that list.
 */
enum class CalibrationLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    /**
     * Entries older than this are left out of the fit — see `Calibration.ignoreEntriesBefore`.
     * Written when a pre-soak sensor is promoted, whose session is dated before the swap.
     */
    EntriesValidFrom("calibration_entries_valid_from", 0L, exportable = false)
}
