package app.aaps.plugins.aps.keys

import app.aaps.core.keys.PreferenceType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIHealthConnectPermissionActivityMTR
import app.aaps.plugins.aps.openAPSAIMI.sos.AIMIEmergencySosPermissionActivityMTR

enum class ApsIntentKey(
    override val key: String,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.URL,
    override val urlResId: Int? = null,
    override val activityClass: Class<*>? = null,
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
        titleResId = R.string.openapsama_link_to_preference_json_doc_txt,
        preferenceType = PreferenceType.URL,
        urlResId = R.string.openapsama_link_to_preference_json_doc
    ),

    PkpdSetup(
        key = "aimi_pkpd_setup_compose",
        titleResId = R.string.aimi_pkpd_compose_title,
        summaryResId = R.string.aimi_pkpd_compose_summary,
        preferenceType = PreferenceType.ACTIVITY,
    ),

    AimiControlCenter(
        key = "aimi_control_center_compose",
        titleResId = R.string.aimi_control_center_entry_title,
        summaryResId = R.string.aimi_control_center_entry_summary,
        preferenceType = PreferenceType.ACTIVITY,
    ),

    AimiSettingsAudit(
        key = "aimi_settings_audit_compose",
        titleResId = R.string.aimi_settings_audit_entry_title,
        summaryResId = R.string.aimi_settings_audit_entry_summary,
        preferenceType = PreferenceType.ACTIVITY,
    ),

    HormonitorViewer(
        key = "aimi_hormonitor_viewer_compose",
        titleResId = R.string.aimi_hormonitor_viewer_title,
        summaryResId = R.string.aimi_hormonitor_viewer_summary,
        preferenceType = PreferenceType.ACTIVITY,
    ),

    AimiSosPermissions(
        key = "aimi_sos_permissions_compose",
        titleResId = R.string.aimi_sos_permissions_title,
        summaryResId = R.string.aimi_sos_permissions_summary,
        preferenceType = PreferenceType.ACTIVITY,
        activityClass = AIMIEmergencySosPermissionActivityMTR::class.java,
    ),

    AimiHealthConnectPermissions(
        key = "aimi_physio_hc_permissions_compose",
        titleResId = R.string.aimi_physio_hc_permissions_title,
        summaryResId = R.string.aimi_physio_hc_permissions_summary,
        preferenceType = PreferenceType.ACTIVITY,
        activityClass = AIMIHealthConnectPermissionActivityMTR::class.java,
    ),

    AimiPhysioPatternCatalogInfo(
        key = "aimi_physio_pattern_catalog_info",
        titleResId = R.string.aimi_physio_pattern_catalog_title,
        summaryResId = R.string.aimi_physio_pattern_catalog_summary,
        preferenceType = PreferenceType.CLICK,
    ),

    AimiHypoRiskAlarmInfo(
        key = "aimi_hypo_risk_alarm_info",
        titleResId = R.string.hypo_risk_notification_title,
        summaryResId = R.string.aimi_hypo_risk_alarm_summary,
        preferenceType = PreferenceType.CLICK,
    ),
}
