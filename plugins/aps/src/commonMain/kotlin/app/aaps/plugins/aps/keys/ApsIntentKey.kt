package app.aaps.plugins.aps.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings

enum class ApsIntentKey(
    override val key: String,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.URL,
    // urlRef rather than urlResId: a resource id is an Android Int and means nothing off Android.
    override val urlRef: TextRef? = null,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = false
) : IntentPreferenceKey {

    LinkToDocs(
        key = "aps_link_to_docs",
        title = ApsStrings.openapsama_link_to_preference_json_doc_txt,
        preferenceType = PreferenceType.URL,
        urlRef = ApsStrings.openapsama_link_to_preference_json_doc
    ),

    AimiControlCenter(
        key = "aimi_control_center_compose",
        title = ApsStrings.pref_title_aimi_control_center,
        summary = ApsStrings.pref_summary_aimi_control_center,
        preferenceType = PreferenceType.CLICK,
    ),

    AimiSosPermissions(
        key = "aimi_sos_permissions_compose",
        title = ApsStrings.pref_title_aimi_sos_permissions,
        summary = ApsStrings.pref_summary_aimi_sos_permissions,
        preferenceType = PreferenceType.ACTIVITY,
    ),

    AimiHypoRiskAlarmInfo(
        key = "aimi_hypo_risk_alarm_info",
        title = ApsStrings.pref_title_aimi_hypo_risk_alarm_info,
        summary = ApsStrings.pref_summary_aimi_hypo_risk_alarm_info,
        preferenceType = PreferenceType.CLICK,
    ),

    AimiPhysioPatternCatalogInfo(
        key = "aimi_physio_pattern_catalog_info",
        title = ApsStrings.pref_title_aimi_physio_pattern_catalog_info,
        summary = ApsStrings.pref_summary_aimi_physio_pattern_catalog_info,
        preferenceType = PreferenceType.CLICK,
    ),

    AimiHealthConnectPermissions(
        key = "aimi_physio_hc_permissions_compose",
        title = ApsStrings.pref_title_aimi_health_connect_permissions,
        summary = ApsStrings.pref_summary_aimi_health_connect_permissions,
        preferenceType = PreferenceType.ACTIVITY,
    ),

    HormonitorViewer(
        key = "aimi_hormonitor_viewer_compose",
        title = ApsStrings.pref_title_hormonitor_viewer,
        summary = ApsStrings.pref_summary_hormonitor_viewer,
        preferenceType = PreferenceType.CLICK,
    ),

    PkpdSetup(
        key = "aimi_pkpd_setup_compose",
        title = ApsStrings.pref_title_pkpd_setup,
        summary = ApsStrings.pref_summary_pkpd_setup,
        preferenceType = PreferenceType.CLICK,
    ),
}
