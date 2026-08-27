package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.source.R

enum class Libre3BooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    private val titleResId: Int,
    private val summaryResId: Int? = null,
    override val defaultedBySM: Boolean = false,
    override val calculatedDefaultValue: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = true,
    override val exportable: Boolean = false,
) : BooleanPreferenceKey {

    /**
     * Routes [app.aaps.plugins.libre3.Libre3CgmDrivers.default] to the real driver instead of the
     * stub. Default off on purpose: the real driver must stay unreachable until the NFC step and
     * the crypto vectors are proven, and until the user has confirmed it on a real sensor.
     */
    UseRealSkeleton(
        key = "libre3_use_real_skeleton",
        defaultValue = false,
        titleResId = R.string.libre3_use_real_skeleton,
        summaryResId = R.string.libre3_use_real_skeleton_summary,
        engineeringModeOnly = true,
        exportable = false,
    )
    ;

    override val title: TextRef = TextRef.AndroidRes(titleResId)
    override val summary: TextRef? = summaryResId?.let { TextRef.AndroidRes(it) }
}
