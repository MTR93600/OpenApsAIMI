package app.aaps.plugins.sync.garmin

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/**
 * The watches Garmin Connect handed back. The SDK does not keep them, and another phone's list
 * would name watches that are not paired here, so this stays on this phone.
 *
 * `iosMain`, so it is not in the Android preference schema.
 */
enum class GarminIosStringNonKey(
    override val key: String,
    override val defaultValue: String,
    override val exportable: Boolean = false,
) : StringNonPreferenceKey {

    ConnectIqDevices("garmin_connectiq_devices", ""),
}
