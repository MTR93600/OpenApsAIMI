package app.aaps.plugins.source.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.source.SourceStrings

/** The rows of the Eversense settings screen that open a screen or run an action. */
enum class EversenseIntentKey(
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
    override val exportable: Boolean = false
) : IntentPreferenceKey {

    EversenseStatus(
        key = "eversense_status",
        title = SourceStrings.eversense_status_title
    ),
    EversenseCalibration(
        key = "eversense_calibration_launch",
        title = SourceStrings.eversense_calibration_action
    ),
    EversensePlacement(
        key = "eversense_placement_launch",
        title = SourceStrings.eversense_placement_title
    ),
    EversenseSignOut(
        key = "eversense_sign_out",
        title = SourceStrings.eversense_sign_out
    ),
    EversenseAbout(
        key = "eversense_about",
        title = SourceStrings.eversense_about_title,
        summary = SourceStrings.eversense_about_summary
    )
}
