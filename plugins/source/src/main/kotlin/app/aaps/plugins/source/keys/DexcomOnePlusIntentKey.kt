package app.aaps.plugins.source.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.plugins.source.R

enum class DexcomOnePlusIntentKey(
    override val key: String,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.ACTIVITY,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = false
) : IntentPreferenceKey {
    Status(
        key = "dexcom_oneplus_status",
        titleResId = R.string.dexcom_oneplus_status_title,
        summaryResId = R.string.dexcom_oneplus_status_summary
    ),
    Start(
        key = "dexcom_oneplus_start",
        titleResId = R.string.dexcom_oneplus_start_title,
        summaryResId = R.string.dexcom_oneplus_start_summary
    ),
    Warmup(
        key = "dexcom_oneplus_warmup",
        titleResId = R.string.dexcom_oneplus_warmup_title,
        summaryResId = R.string.dexcom_oneplus_warmup_summary
    ),
}
