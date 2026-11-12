package app.aaps.plugins.sync.garmin

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

/**
 * The Garmin settings row that opens Garmin Connect's device picker. iPhone only, so it is not in
 * the Android preference schema.
 */
enum class GarminIosIntentKey(
    override val key: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.CLICK,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = false,
) : IntentPreferenceKey {

    SelectDevices(
        key = "garmin_ios_select_devices",
        title = SyncStrings.garmin_select_devices,
        summary = SyncStrings.garmin_select_devices_summary,
    ),
}
