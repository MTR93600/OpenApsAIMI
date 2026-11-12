package app.aaps.plugins.sync.garmin.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

enum class GarminBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val defaultedBySM: Boolean = false,
    override val hideParentScreenIfHidden: Boolean = false,
    override val summary: TextRef? = null,
    override val exportable: Boolean = true,
) : BooleanPreferenceKey {

    LocalHttpServer("communication_http", false, title = SyncStrings.garmin_local_http_server, defaultedBySM = true, hideParentScreenIfHidden = true),

    /**
     * iPhone only: push the glucose message through the Connect IQ companion SDK.
     *
     * On by default there. Android registers the key (it lives with the other Garmin keys) and never
     * reads it, so the Connect IQ messenger on Android is unchanged. Not exported: it describes this
     * phone's channel, not the therapy.
     */
    ConnectIqPush(
        key = "garmin_connectiq_push_ios",
        defaultValue = true,
        title = SyncStrings.garmin_connectiq_push_ios,
        summary = SyncStrings.garmin_connectiq_push_ios_summary,
        exportable = false,
    ),

    /**
     * iPhone only: keep AAPS awake in the background so a watch face always gets an answer from the
     * local HTTP server, and its steps and heart rate reach AAPS. See `IosKeepAwake`.
     *
     * Off by default, because it costs battery. Android registers the key and never reads it. Not
     * exported: it describes this phone, not the therapy.
     */
    KeepAwakeIos(
        key = "garmin_keep_awake_ios",
        defaultValue = false,
        title = SyncStrings.garmin_keep_awake_ios,
        summary = SyncStrings.garmin_keep_awake_ios_summary,
        exportable = false,
    ),
    ;

}
