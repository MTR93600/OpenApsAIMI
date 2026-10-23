package app.aaps.plugins.sync.garmin.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

/** When [GarminIntNonKey.LastTotalSteps] was stored, in ms. Not exported, for the same reason. */
enum class GarminLongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = false
) : LongNonPreferenceKey {

    LastTotalStepsTime("garmin_http_last_steps_ts", 0L),
}
