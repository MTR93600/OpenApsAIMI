package app.aaps.plugins.source.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.plugins.source.R

enum class DexcomOnePlusBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
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
     * Routes [app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDrivers.default] to the Real skeleton
     * (still fails closed at GATT/auth). For A3 spike only — not a production BLE claim.
     */
    UseRealSkeleton(
        key = "dexcom_oneplus_use_real_skeleton",
        defaultValue = true,
        titleResId = R.string.dexcom_oneplus_use_real_skeleton,
        summaryResId = R.string.dexcom_oneplus_use_real_skeleton_summary,
        engineeringModeOnly = true,
        exportable = false,
    ),
}
