package app.aaps.plugins.sync.liveactivity

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.sync.SyncStrings

/**
 * Settings of the iOS Live Activity. iPhone only, so they are not in the Android preference schema.
 * Not exported: they describe this phone's lock screen, not the therapy.
 */
enum class LiveActivityBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val defaultedBySM: Boolean = false,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = false,
) : BooleanPreferenceKey {

    /** The one switch. Off by default. */
    LiveActivityEnabled(
        key = "live_activity_enabled",
        defaultValue = false,
        title = SyncStrings.live_activity_enabled,
        summary = SyncStrings.live_activity_enabled_summary,
    ),
    LiveActivityShowChart(
        key = "live_activity_show_chart",
        defaultValue = true,
        title = SyncStrings.live_activity_show_chart,
        summary = SyncStrings.live_activity_show_chart_summary,
    ),
    LiveActivityShowIobCob(
        key = "live_activity_show_iob_cob",
        defaultValue = true,
        title = SyncStrings.live_activity_show_iob_cob,
    ),
    LiveActivityShowLoop(
        key = "live_activity_show_loop",
        defaultValue = true,
        title = SyncStrings.live_activity_show_loop,
    ),
    ;

    /** The rows only matter while the Live Activity is on. */
    override val dependency: BooleanPreferenceKey?
        get() = if (this == LiveActivityEnabled) null else LiveActivityEnabled
}
