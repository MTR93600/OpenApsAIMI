package app.aaps.plugins.aps.openAPSAIMI.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

enum class AimiStringKey(
    override val key: String,
    override val defaultValue: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, TextRef> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val hideParentScreenIfHidden: Boolean = false,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {
    PregnancyDueDateString(
        key = "aimi_pregnancy_due_date_string",
        defaultValue = "",
        title = ApsStrings.OApsAIMI_PregnancyDueDate_title,
        summary = ApsStrings.OApsAIMI_PregnancyDueDate_summary
    ),
    RemoteControlPin(
        key = "aimi_remote_control_pin",
        defaultValue = "",
        title = ApsStrings.OApsAIMI_RemoteControlPin_title,
        summary = ApsStrings.OApsAIMI_RemoteControlPin_summary,
        isPin = true
    ),

    OuraPersonalAccessToken(
        key = "aimi_oura_personal_access_token",
        defaultValue = "",
        title = ApsStrings.aimi_oura_pat_title,
        summary = ApsStrings.aimi_oura_pat_summary,
        isPassword = true,
        exportable = false,
    ),

    /**
     * Steps and heart-rate source.
     * Same stored key as dump `UnifiedActivityProviderMTR.PREF_KEY_SOURCE_MODE`.
     */
    ActivitySourceMode(
        key = "aimi_activity_source_mode",
        defaultValue = "auto",
        title = ApsStrings.pref_aimi_steps_source_title,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "prefer_wear" to ApsStrings.pref_aimi_steps_source_wear,
            "auto" to ApsStrings.pref_aimi_steps_source_auto,
            "hc_only" to ApsStrings.pref_aimi_steps_source_hc,
            "disabled" to ApsStrings.pref_aimi_steps_source_disabled,
        ),
    ),

    OApsAIMIPkpdStateDominantBranch(
        key = "aimi_pkpd_state_dominant_branch",
        defaultValue = "",
        title = ApsStrings.aimi_pkpd_state_internal_title,
        showInApsMode = false,
        showInNsClientMode = false,
        showInPumpControlMode = false
    ),

    /** Last material `PEAK_GOV:` line for APS console / debugging (RFC H.4). */
    OApsAIMIPkpdLastPeakGovLogLine(
        key = "aimi_pkpd_state_last_peak_gov_log",
        defaultValue = "",
        title = ApsStrings.aimi_pkpd_state_internal_title,
        showInApsMode = false,
        showInNsClientMode = false,
        showInPumpControlMode = false
    ),

    /** Last `PEAK_GOV` line echoed to APS console (dedup in dump tick). */
    OApsAIMIPkpdLastPeakGovConsoleEchoed(
        key = "aimi_pkpd_state_last_peak_gov_console_echoed",
        defaultValue = "",
        title = ApsStrings.aimi_pkpd_state_internal_title,
        showInApsMode = false,
        showInNsClientMode = false,
        showInPumpControlMode = false
    ),
}
