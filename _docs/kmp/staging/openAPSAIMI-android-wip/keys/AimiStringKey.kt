package app.aaps.plugins.aps.openAPSAIMI.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR

enum class AimiStringKey(
    override val key: String,
    override val defaultValue: String,
    override val titleResId: Int,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, Int> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val hideParentScreenIfHidden: Boolean = false,
    override val dependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val negativeDependency: app.aaps.core.keys.interfaces.BooleanPreferenceKey? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {
    PregnancyDueDateString(
        key = "aimi_pregnancy_due_date_string",
        defaultValue = "",
        titleResId = R.string.OApsAIMI_PregnancyDueDate_title,
        summaryResId = R.string.OApsAIMI_PregnancyDueDate_summary
    ),
    RemoteControlPin(
        key = "aimi_remote_control_pin",
        defaultValue = "",
        titleResId = R.string.OApsAIMI_RemoteControlPin_title,
        summaryResId = R.string.OApsAIMI_RemoteControlPin_summary,
        isPin = true
    ),

    OuraPersonalAccessToken(
        key = "aimi_oura_personal_access_token",
        defaultValue = "",
        titleResId = R.string.aimi_oura_pat_title,
        summaryResId = R.string.aimi_oura_pat_summary,
        isPassword = true,
        exportable = false,
    ),

    /** Steps & heart-rate source (same key as [UnifiedActivityProviderMTR.PREF_KEY_SOURCE_MODE]). */
    ActivitySourceMode(
        key = UnifiedActivityProviderMTR.PREF_KEY_SOURCE_MODE,
        defaultValue = UnifiedActivityProviderMTR.DEFAULT_MODE,
        titleResId = R.string.pref_aimi_steps_source_title,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            UnifiedActivityProviderMTR.MODE_PREFER_WEAR to R.string.pref_aimi_steps_source_wear,
            UnifiedActivityProviderMTR.MODE_AUTO_FALLBACK to R.string.pref_aimi_steps_source_auto,
            UnifiedActivityProviderMTR.MODE_HEALTH_CONNECT_ONLY to R.string.pref_aimi_steps_source_hc,
            UnifiedActivityProviderMTR.MODE_DISABLED to R.string.pref_aimi_steps_source_disabled,
        ),
    ),

    OApsAIMIPkpdStateDominantBranch(
        key = "aimi_pkpd_state_dominant_branch",
        defaultValue = "",
        titleResId = 0,
        showInApsMode = false,
        showInNsClientMode = false,
        showInPumpControlMode = false
    ),

    /** Last material `PEAK_GOV:` line for APS console / debugging (RFC H.4). */
    OApsAIMIPkpdLastPeakGovLogLine(
        key = "aimi_pkpd_state_last_peak_gov_log",
        defaultValue = "",
        titleResId = 0,
        showInApsMode = false,
        showInNsClientMode = false,
        showInPumpControlMode = false
    ),

    /** Last `PEAK_GOV` line echoed to APS console (dedup in DetermineBasalAIMI2). */
    OApsAIMIPkpdLastPeakGovConsoleEchoed(
        key = "aimi_pkpd_state_last_peak_gov_console_echoed",
        defaultValue = "",
        titleResId = 0,
        showInApsMode = false,
        showInNsClientMode = false,
        showInPumpControlMode = false
    ),
}
