package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey

enum class UnitDoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val minMgdl: Int,
    override val maxMgdl: Int,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val sync: SyncSpec? = null
) : UnitDoublePreferenceKey {

    OverviewLowMark(key = "low_mark", defaultValue = 72.0, minMgdl = 25, maxMgdl = 160, titleResId = R.string.pref_title_low_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    OverviewHighMark(key = "high_mark", defaultValue = 180.0, minMgdl = 90, maxMgdl = 250, titleResId = R.string.pref_title_high_mark, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsLgsThreshold(
        key = "lgsThreshold",
        defaultValue = 65.0,
        minMgdl = 60,
        maxMgdl = 100,
        titleResId = R.string.pref_title_lgs_threshold,
        summaryResId = R.string.lgs_threshold_summary,
        defaultedBySM = true,
        dependency = BooleanKey.ApsUseDynamicSensitivity,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AlertHypoThreshold(
        key = "alert_hypo_threshold",
        defaultValue = 70.0,
        minMgdl = 50,
        maxMgdl = 100,
        titleResId = R.string.pref_title_alert_hypo_threshold,
        dependency = BooleanKey.AlertHypo
    ),
    AlertHyperThreshold(
        key = "alert_hyper_threshold",
        defaultValue = 250.0,
        minMgdl = 140,
        maxMgdl = 400,
        titleResId = R.string.pref_title_alert_hyper_threshold,
        dependency = BooleanKey.AlertHyper
    ),
    // Rapid-fall drop magnitude: a glucose *difference* (mg/dL) — UnitDoubleKey still converts it
    // correctly for display (30 mg/dL ≈ 1.7 mmol/L).
    AlertRapidFallDrop(
        key = "alert_rapid_fall_drop",
        defaultValue = 30.0,
        minMgdl = 15,
        maxMgdl = 60,
        titleResId = R.string.pref_title_alert_rapid_fall_drop,
        dependency = BooleanKey.AlertRapidFall
    )
}