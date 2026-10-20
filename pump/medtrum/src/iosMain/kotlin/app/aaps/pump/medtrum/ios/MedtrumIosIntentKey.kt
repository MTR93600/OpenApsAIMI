package app.aaps.pump.medtrum.ios

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * The screens this plugin offers in its settings on an iPhone.
 *
 * One for now. Android reaches the same scan through its patch wizard, which this app does not
 * have here yet, so the one step a person needs on an iPhone - telling the app which pump is
 * theirs - gets a screen of its own.
 *
 * `iosMain`, so none of this is in the Android preference schema.
 */
enum class MedtrumIosIntentKey(
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

    PickPump(
        key = "medtrum_ios_pick_pump",
        title = MedtrumStrings.medtrum_pick_pump_title,
        summary = MedtrumStrings.medtrum_pick_pump_summary
    )
}
