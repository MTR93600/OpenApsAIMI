package app.aaps.plugins.sync.garmin.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

/**
 * State of the Garmin step counter, kept on this phone only. A watch that sends only a day total
 * (`steps=`) needs the last total to work out how many steps are new. Not exported: another phone's
 * last total would turn this phone's next reading into a wrong delta.
 */
enum class GarminIntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = false
) : IntNonPreferenceKey {

    LastTotalSteps("garmin_http_last_steps", -1),
}
