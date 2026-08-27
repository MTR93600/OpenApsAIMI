package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.TextRef

enum class BooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.SWITCH,
    override val calculatedDefaultValue: Boolean = false,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val engineeringModeOnly: Boolean = false,
    override val exportable: Boolean = true,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
    override val sync: SyncSpec? = null
) : BooleanPreferenceKey {

    GeneralSimpleMode(key = "simple_mode", defaultValue = true, title = KeysStrings.pref_title_simple_mode, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    GeneralInsulinConcentration(
        key = "insulin_concentration_enabled", defaultValue = false, title = KeysStrings.pref_title_insulin_concentration, summary = KeysStrings.pref_summary_insulin_concentration,
        defaultedBySM = true,
        enabledCondition = PreferenceEnabledCondition { it.isConcentrationEnabled },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    OverviewKeepScreenOn(key = "keep_screen_on", defaultValue = false, title = KeysStrings.pref_title_keep_screen_on, summary = KeysStrings.pref_summary_keep_screen_on, calculatedDefaultValue = true),
    OverviewShowTreatmentButton(key = "show_treatment_button", defaultValue = false, title = KeysStrings.pref_title_show_treatment_button, defaultedBySM = true),
    OverviewShowWizardButton(key = "show_wizard_button", defaultValue = true, title = KeysStrings.pref_title_show_wizard_button, defaultedBySM = true),
    OverviewShowInsulinButton(key = "show_insulin_button", defaultValue = true, title = KeysStrings.pref_title_show_insulin_button, defaultedBySM = true),
    OverviewShowCarbsButton(key = "show_carbs_button", defaultValue = true, title = KeysStrings.pref_title_show_carbs_button, defaultedBySM = true),
    OverviewShowCgmButton(key = "show_cgm_button", defaultValue = false, title = KeysStrings.pref_title_show_cgm_button, summary = KeysStrings.pref_summary_show_cgm_button, defaultedBySM = true, showInNsClientMode = false),
    OverviewShowCalibrationButton(
        key = "show_calibration_button",
        defaultValue = false,
        title = KeysStrings.pref_title_show_calibration_button,
        summary = KeysStrings.pref_summary_show_calibration_button,
        defaultedBySM = true,
        showInNsClientMode = false
    ),
    OverviewShowNotesInDialogs(key = "show_notes_entry_dialogs", defaultValue = false, title = KeysStrings.pref_title_show_notes_in_dialogs, defaultedBySM = true),
    OverviewUseBolusAdvisor("use_bolus_advisor", true, KeysStrings.pref_title_use_bolus_advisor, KeysStrings.pref_summary_use_bolus_advisor, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    OverviewUseBolusReminder("use_bolus_reminder", true, KeysStrings.pref_title_use_bolus_reminder, KeysStrings.pref_summary_use_bolus_reminder, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),

    @Deprecated("Remove support")
    OverviewUseSuperBolus("key_usersuperbolus", false, KeysStrings.pref_title_use_super_bolus, KeysStrings.pref_summary_use_super_bolus, defaultedBySM = true, hideParentScreenIfHidden = true),

    PumpBtWatchdog(
        "bt_watchdog", false, KeysStrings.pref_title_bt_watchdog, KeysStrings.pref_summary_bt_watchdog,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    AlertMissedBgReading("enable_missed_bg_readings", false, KeysStrings.pref_title_alert_missed_bg_reading),
    AlertPumpUnreachable("enable_pump_unreachable_alert", true, KeysStrings.pref_title_alert_pump_unreachable),
    AlertCarbsRequired("enable_carbs_required_alert_local", true, KeysStrings.pref_title_alert_carbs_required),
    AlertUrgentAsAndroidNotification("raise_urgent_alarms_as_android_notification", true, KeysStrings.pref_title_alert_urgent_as_android_notification),
    AlertIncreaseVolume("gradually_increase_notification_volume", true, KeysStrings.pref_title_alert_increase_volume),
    AlertOverrideDoNotDisturb("alert_override_dnd", true, KeysStrings.pref_title_alert_override_dnd, KeysStrings.pref_summary_alert_override_dnd, defaultedBySM = true),

    BgSourceUploadToNs("dexcomg5_nsupload", true, KeysStrings.pref_title_bg_source_upload_to_ns, defaultedBySM = true, hideParentScreenIfHidden = true),
    BgSourceCreateSensorChange("dexcom_lognssensorchange", true, KeysStrings.pref_title_bg_source_create_sensor_change, KeysStrings.pref_summary_bg_source_create_sensor_change, defaultedBySM = true),
    BgSourceRandomBgRandomize("randombg_randomize", true, KeysStrings.pref_title_random_bg_randomize, KeysStrings.pref_summary_random_bg_randomize, defaultedBySM = true),

    ApsUseDynamicSensitivity("use_dynamic_sensitivity", false, KeysStrings.pref_title_aps_use_dynamic_sensitivity, KeysStrings.pref_summary_aps_use_dynamic_sensitivity, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsUseAutosens(
        "openapsama_useautosens", true, KeysStrings.pref_title_aps_use_autosens, defaultedBySM = true,
        // Hidden only while the active APS both offers dynamic sensitivity and has it enabled.
        // A plain negativeDependency on ApsUseDynamicSensitivity would also hide it on algorithms
        // whose screens never show that toggle (AMA, AutoISF), with no way to reveal it (issue #4482).
        visibility = ElementVisibility { !(it.apsOffersDynamicSensitivity && it.preferences.get(ApsUseDynamicSensitivity)) },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmb("use_smb", true, KeysStrings.pref_title_aps_use_smb, KeysStrings.pref_summary_aps_use_smb, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsUseSmbWithHighTt(
        "enableSMB_with_high_temptarget",
        false,
        KeysStrings.pref_title_aps_use_smb_with_high_tt,
        KeysStrings.pref_summary_aps_use_smb_with_high_tt,
        defaultedBySM = true,
        dependency = ApsUseSmb,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbAlways(
        "enableSMB_always", true, KeysStrings.pref_title_aps_use_smb_always, KeysStrings.pref_summary_aps_use_smb_always, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility.ADVANCED_FILTERING,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbWithCob(
        "enableSMB_with_COB", true, KeysStrings.pref_title_aps_use_smb_with_cob, KeysStrings.pref_summary_aps_use_smb_with_cob, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility { !it.preferences.get(ApsUseSmbAlways) || !it.advancedFilteringSupported },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbWithLowTt(
        "enableSMB_with_temptarget", true, KeysStrings.pref_title_aps_use_smb_with_low_tt, KeysStrings.pref_summary_aps_use_smb_with_low_tt, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility { !it.preferences.get(ApsUseSmbAlways) || !it.advancedFilteringSupported },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseSmbAfterCarbs(
        "enableSMB_after_carbs", true, KeysStrings.pref_title_aps_use_smb_after_carbs, KeysStrings.pref_summary_aps_use_smb_after_carbs, defaultedBySM = true, dependency = ApsUseSmb,
        visibility = ElementVisibility { !it.preferences.get(ApsUseSmbAlways) && it.advancedFilteringSupported },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseUam("use_uam", true, KeysStrings.pref_title_aps_use_uam, KeysStrings.pref_summary_aps_use_uam, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsSensitivityRaisesTarget(
        "sensitivity_raises_target", true, KeysStrings.pref_title_aps_sensitivity_raises_target, KeysStrings.pref_summary_aps_sensitivity_raises_target, defaultedBySM = true,
        visibility = ElementVisibility {
            if (it.preferences.get(ApsUseDynamicSensitivity)) {
                it.preferences.get(ApsDynIsfAdjustSensitivity)
            } else {
                it.preferences.get(ApsUseAutosens)
            }
        },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsResistanceLowersTarget(
        "resistance_lowers_target", true, KeysStrings.pref_title_aps_resistance_lowers_target, KeysStrings.pref_summary_aps_resistance_lowers_target, defaultedBySM = true,
        visibility = ElementVisibility {
            if (it.preferences.get(ApsUseDynamicSensitivity)) {
                it.preferences.get(ApsDynIsfAdjustSensitivity)
            } else {
                it.preferences.get(ApsUseAutosens)
            }
        },
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAlwaysUseShortDeltas(
        "always_use_shortavg",
        false,
        KeysStrings.pref_title_aps_always_use_short_deltas,
        KeysStrings.pref_summary_aps_always_use_short_deltas,
        defaultedBySM = true,
        hideParentScreenIfHidden = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsDynIsfAdjustSensitivity(
        "dynisf_adjust_sensitivity",
        false,
        KeysStrings.pref_title_aps_dynisf_adjust_sensitivity,
        KeysStrings.pref_summary_aps_dynisf_adjust_sensitivity,
        defaultedBySM = true,
        dependency = ApsUseDynamicSensitivity,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaAutosensAdjustTargets(
        "autosens_adjust_targets",
        true,
        KeysStrings.pref_title_aps_autosens_adjust_targets,
        KeysStrings.pref_summary_aps_autosens_adjust_targets,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfHighTtRaisesSens(
        "high_temptarget_raises_sensitivity",
        false,
        KeysStrings.pref_title_aps_high_tt_raises_sensitivity,
        KeysStrings.pref_summary_aps_high_tt_raises_sensitivity,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLowTtLowersSens(
        "low_temptarget_lowers_sensitivity",
        false,
        KeysStrings.pref_title_aps_low_tt_lowers_sensitivity,
        KeysStrings.pref_summary_aps_low_tt_lowers_sensitivity,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsUseAutoIsfWeights("openapsama_enable_autoISF", false, KeysStrings.pref_title_aps_use_autoisf_weights, KeysStrings.pref_summary_aps_use_autoisf_weights, defaultedBySM = true, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsAutoIsfSmbOnEvenTarget(
        "Enable alternative activation of SMB always",
        false,
        KeysStrings.pref_title_aps_smb_on_even_target,
        KeysStrings.pref_summary_aps_smb_on_even_target,
        defaultedBySM = true,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    MaintenanceEnableFabric("enable_fabric2", true, KeysStrings.pref_title_maintenance_enable_fabric, defaultedBySM = true, hideParentScreenIfHidden = true),

    // Master-only (not a follower client): unattended settings export backs up the local config, which on a
    // client is derived from the master. showInNsClientMode=false hides it in apsMode + pumpControlMode only;
    // hideParentScreenIfHidden collapses the now-empty "Unattended Settings Export" subscreen on a client.
    MaintenanceEnableExportSettingsAutomation("enable_unattended_export", false, KeysStrings.pref_title_maintenance_enable_export_automation, defaultedBySM = false, showInNsClientMode = false, hideParentScreenIfHidden = true),

    AutotuneAutoSwitchProfile("autotune_auto", false, KeysStrings.pref_title_autotune_auto_switch_profile, KeysStrings.pref_summary_autotune_auto_switch_profile),
    AutotuneCategorizeUamAsBasal("categorize_uam_as_basal", false, KeysStrings.pref_title_autotune_categorize_uam_as_basal, KeysStrings.pref_summary_autotune_categorize_uam_as_basal),
    AutotuneTuneInsulinCurve("autotune_tune_insulin_curve", false, KeysStrings.pref_title_autotune_tune_insulin_curve),
    AutotuneCircadianIcIsf("autotune_circadian_ic_isf", false, KeysStrings.pref_title_autotune_circadian_ic_isf, KeysStrings.pref_summary_autotune_circadian_ic_isf),
    AutotuneAdditionalLog("autotune_additional_log", false, KeysStrings.pref_title_autotune_additional_log),

    SmsAllowRemoteCommands("smscommunicator_remotecommandsallowed", false, KeysStrings.pref_title_sms_allow_remote_commands),
    SmsReportPumpUnreachable("smscommunicator_report_pump_unreachable", true, KeysStrings.pref_title_sms_report_pump_unreachable, KeysStrings.pref_summary_sms_report_pump_unreachable),

    VirtualPumpStatusUpload("virtualpump_uploadstatus", false, KeysStrings.pref_title_virtual_pump_status_upload, showInNsClientMode = false),
    NsClientUploadData("ns_upload", true, KeysStrings.pref_title_ns_upload_data, KeysStrings.pref_summary_ns_upload_data, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptCgmData("ns_receive_cgm", false, KeysStrings.pref_title_ns_receive_cgm, KeysStrings.pref_summary_ns_receive_cgm, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptProfileStore("ns_receive_profile_store", false, KeysStrings.pref_title_ns_receive_profile_store, KeysStrings.pref_summary_ns_receive_profile_store, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTempTarget("ns_receive_temp_target", false, KeysStrings.pref_title_ns_receive_temp_target, KeysStrings.pref_summary_ns_receive_temp_target, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptProfileSwitch("ns_receive_profile_switch", false, KeysStrings.pref_title_ns_receive_profile_switch, KeysStrings.pref_summary_ns_receive_profile_switch, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptInsulin("ns_receive_insulin", false, KeysStrings.pref_title_ns_receive_insulin, KeysStrings.pref_summary_ns_receive_insulin, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptCarbs("ns_receive_carbs", false, KeysStrings.pref_title_ns_receive_carbs, KeysStrings.pref_summary_ns_receive_carbs, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTherapyEvent("ns_receive_therapy_events", false, KeysStrings.pref_title_ns_receive_therapy_event, KeysStrings.pref_summary_ns_receive_therapy_event, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptRunningMode("ns_receive_running_mode", false, KeysStrings.pref_title_ns_receive_running_mode, KeysStrings.pref_summary_ns_receive_running_mode, showInNsClientMode = false, hideParentScreenIfHidden = true),
    NsClientAcceptTbrEb("ns_receive_tbr_eb", false, KeysStrings.pref_title_ns_receive_tbr_eb, KeysStrings.pref_summary_ns_receive_tbr_eb, showInNsClientMode = false, engineeringModeOnly = true),
    NsClientNotificationsFromAlarms("ns_alarms", false, KeysStrings.pref_title_ns_notifications_from_alarms, calculatedDefaultValue = true),
    NsClientNotificationsFromAnnouncements("ns_announcements", false, KeysStrings.pref_title_ns_notifications_from_announcements, calculatedDefaultValue = true),
    NsClientUseCellular("ns_cellular", true, KeysStrings.pref_title_ns_use_cellular),
    NsClientUseRoaming("ns_allow_roaming", true, KeysStrings.pref_title_ns_use_roaming, dependency = NsClientUseCellular),
    NsClientUseWifi("ns_wifi", true, KeysStrings.pref_title_ns_use_wifi),
    NsClientUseOnBattery("ns_battery", true, KeysStrings.pref_title_ns_use_on_battery),
    NsClientUseOnCharging("ns_charging", true, KeysStrings.pref_title_ns_use_on_charging),
    NsClientLogAppStart("ns_log_app_started_event", false, KeysStrings.pref_title_ns_log_app_start, calculatedDefaultValue = true),
    NsClientCreateAnnouncementsFromErrors("ns_create_announcements_from_errors", false, KeysStrings.pref_title_ns_create_announcements_from_errors, calculatedDefaultValue = true, showInNsClientMode = false),
    NsClientCreateAnnouncementsFromCarbsReq("ns_create_announcements_from_carbs_req", false, KeysStrings.pref_title_ns_create_announcements_from_carbs_req, calculatedDefaultValue = true, showInNsClientMode = false),
    NsClientSlowSync("ns_sync_slow", false, KeysStrings.pref_title_ns_slow_sync),
    NsClient3UseWs("ns_use_ws", true, KeysStrings.pref_title_ns_use_ws, KeysStrings.pref_summary_ns_use_ws),
    NsClientAllowClientControl(
        "ns_allow_client_control", false,
        KeysStrings.pref_title_ns_allow_client_control, KeysStrings.pref_summary_ns_allow_client_control,
        // The rich stop/allow-communication switch lives on the Authorized clients screen; it is ALSO exposed in a
        // "Remote control" category on the NSCv3 settings screen (NSClientV3Plugin.getPreferenceScreenContent) so it
        // is reachable from search. Default OFF, but ON in simple mode (resolved in PreferencesImpl.calculatedDefaultValue). Hidden on a client.
        calculatedDefaultValue = true, showInNsClientMode = false,
        // Remote control rides the WebSocket — hide the toggle (and its single-item "Remote control" parent category)
        // when WS is off, and on a client where the key is already hidden (so the category never shows empty).
        dependency = NsClient3UseWs, hideParentScreenIfHidden = true,
        // Synced master→client (MasterOnly — the client mirrors, never pushes back) so a paired client knows
        // whether the master is accepting commands and can gate its UI. buildSyncedPrefs publishes the EFFECTIVE
        // value for this key (see RunningConfigurationImpl), not the raw default.
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.MasterOnly)
    ),
    OpenHumansWifiOnly("oh_wifi_only", true, KeysStrings.pref_title_openhumans_wifi_only),
    OpenHumansChargingOnly("oh_charging_only", false, KeysStrings.pref_title_openhumans_charging_only),
    XdripSendStatus("xdrip_send_status", false, KeysStrings.pref_title_xdrip_send_status),
    XdripSendDetailedIob("xdripstatus_detailediob", true, KeysStrings.pref_title_xdrip_send_detailed_iob, KeysStrings.pref_summary_xdrip_send_detailed_iob, defaultedBySM = true, hideParentScreenIfHidden = true),
    XdripSendBgi("xdripstatus_showbgi", true, KeysStrings.pref_title_xdrip_send_bgi, KeysStrings.pref_summary_xdrip_send_bgi, defaultedBySM = true, hideParentScreenIfHidden = true),
    WearControl(key = "wearcontrol", defaultValue = false, title = KeysStrings.pref_title_wear_control, summary = KeysStrings.pref_summary_wear_control),
    WearWizardBg(key = "wearwizard_bg", defaultValue = true, title = KeysStrings.pref_title_wear_wizard_bg, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardTt(key = "wearwizard_tt", defaultValue = false, title = KeysStrings.pref_title_wear_wizard_tt, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardTrend(key = "wearwizard_trend", defaultValue = false, title = KeysStrings.pref_title_wear_wizard_trend, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardCob(key = "wearwizard_cob", defaultValue = true, title = KeysStrings.pref_title_wear_wizard_cob, dependency = WearControl, hideParentScreenIfHidden = true),
    WearWizardIob(key = "wearwizard_iob", defaultValue = true, title = KeysStrings.pref_title_wear_wizard_iob, dependency = WearControl, hideParentScreenIfHidden = true),
    WearCustomWatchfaceAuthorization(key = "wear_custom_watchface_autorization", defaultValue = false, title = KeysStrings.pref_title_wear_custom_watchface_authorization),
    WearNotifyOnSmb(key = "wear_notifySMB", defaultValue = true, title = KeysStrings.pref_title_wear_notify_on_smb, summary = KeysStrings.pref_summary_wear_notify_on_smb),
    WearBroadcastData(key = "wear_broadcast_data", defaultValue = false, title = KeysStrings.pref_title_wear_broadcast_data, summary = KeysStrings.pref_summary_wear_broadcast_data, showInApsMode = false, showInPumpControlMode = false),

    SiteRotationManagePump("site_rotation_manage_pump", defaultValue = false, title = KeysStrings.pref_title_site_rotation_manage_pump, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    SiteRotationManageCgm("site_rotation_manage_cgm", defaultValue = false, title = KeysStrings.pref_title_site_rotation_manage_cgm, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),

    // AIMI keys ported from freeze aimi-baseline-2026-08-26
    /** "Last AIMI run" summary card on the hybrid dashboard (Compose + classic). */
    OverviewShowHybridDashboardAimiPulse(
        "overview_show_hybrid_aimi_pulse", false,
        KeysStrings.pref_title_overview_show_hybrid_aimi_pulse,
        KeysStrings.pref_summary_overview_show_hybrid_aimi_pulse,
        defaultedBySM = true
    ),
    OApsAIMIMLtraining("key_enable_ML_training", false, title = KeysStrings.pref_title_oaps_aimi_m_ltraining),
    OApsAIMIEnableBasal("key_enable_basal", false, title = KeysStrings.pref_title_oaps_aimi_enable_basal),
    OApsAIMIEnableStepsFromWatch("count_steps_watch", false, title = KeysStrings.pref_title_oaps_aimi_enable_steps_from_watch),
    OApsAIMIpregnancy("key_use_AimiPregnancy",false, title = KeysStrings.pref_title_oaps_aimi_pregnancy),
    OApsAIMIforcelimits("key_use_AimiForceLimits",false, title = KeysStrings.pref_title_oaps_aimi_forcelimits),
    OApsAIMInight("OApsAIMI_Enable_night",false, title = KeysStrings.pref_title_oaps_aimi_night),
    OApsAIMIhoneymoon("key_use_Aimi_honeymoon",false, title = KeysStrings.pref_title_oaps_aimi_honeymoon),
    OApsAIMIT3cAdaptiveBasalEnabled("key_use_aimi_t3c_adaptive_basal", true, title = KeysStrings.pref_title_oaps_aimi_t3c_adaptive_basal_enabled),
    /** T3C basal-first: let the physiological tree (risk gate) + activity belief shape the BASAL aggressiveness.
     *  Raises the aggressiveness ceiling toward the configured value only when the tree says risk is LOW/MODERATE
     *  and BG is clearly high; reduces (bounded) on exertion. Never enables SMB. Fail-safe when tree unavailable. */
    OApsAIMIT3cPhysioInformedEnabled("key_aimi_t3c_physio_informed", true, title = KeysStrings.pref_title_oaps_aimi_t3c_physio_informed_enabled),
    OApsAIMIAutodriveV3EnhancedGater("key_use_aimi_autodrive_v3_enhanced_gater", false, title = KeysStrings.pref_title_oaps_aimi_autodrive_v3_enhanced_gater),
    OApsAIMIautoDriveActive(key = "key_use_aimi_autodrive_active", defaultValue = true, title = KeysStrings.pref_title_oaps_aimi_auto_drive_active),
    /**
     * Opt-in: on an aggressive rise, let Autodrive V3 deliver at least the user-defined prebolus
     * amount ([DoubleKey.OApsAIMIautodrivePrebolus] / [DoubleKey.OApsAIMIautodrivesmallPrebolus]),
     * as a floor on top of the model SMB. Always re-bounded by V3 safety (post-hypo, maxSMB, IOB,
     * correction aggression). Absorbs the former classic-autodrive aggressive-rise SMB.
     */
    OApsAIMIautodriveAggressiveSmbFloor(
        key = "key_aimi_autodrive_aggressive_smb_floor",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_autodrive_aggressive_smb_floor,
        summary = KeysStrings.pref_summary_aimi_autodrive_aggressive_smb_floor,
        dependency = OApsAIMIautoDriveActive,
    ),
    /**
     * Opt-in: sensor-driven effort protection. Caps SMB when steps/HR indicate current or recent
     * physical effort, independent of any declared AIMI Context activity intent. Reduction-only
     * (fail-safe); never reduces under a stress posture. See docs/AIMI_ARCHITECTURE_MAP.md §11.
     */
    OApsAIMIEffortActivityProtection(
        key = "key_aimi_effort_activity_protection",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_effort_activity_protection,
        summary = KeysStrings.pref_summary_aimi_effort_activity_protection,
    ),
    /**
     * When Autodrive V3 applies a safe command, skip the legacy MPC/PI blender so V3 safety
     * (night cap, post-hypo, weight-aware limits) is not overwritten.
     */
    OApsAIMIautoDriveAuthoritative(
        key = "key_aimi_autodrive_v3_authoritative",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_autodrive_v3_authoritative,
        summary = KeysStrings.pref_summary_aimi_autodrive_v3_authoritative,
        dependency = OApsAIMIautoDriveActive,
    ),
    OApsAIMIwcycle(key = "key_use_Aimi_wcycle",defaultValue = false, title = KeysStrings.pref_title_oaps_aimi_wcycle),
    OApsAIMIWCycleShadow("key_use_Aimi_wcycle_shadow", false, title = KeysStrings.pref_title_oaps_aimi_w_cycle_shadow),
    OApsAIMIWCycleRequireConfirm("key_use_Aimi_wcycle_require_confirm", false, title = KeysStrings.pref_title_oaps_aimi_w_cycle_require_confirm),
    OApsAIMINightGrowthEnabled("key_oaps_aimi_ngr_enabled", true, title = KeysStrings.pref_title_oaps_aimi_night_growth_enabled),
    OApsAIMIPkpdEnabled("key_aimi_pkpd_enabled", false, title = KeysStrings.pref_title_oaps_aimi_pkpd_enabled),
    /** Set after the guided PK/PD setup wizard completes (or is skipped). */
    OApsAIMIPkpdSetupWizardCompleted("key_aimi_pkpd_setup_wizard_completed", false, title = KeysStrings.pref_title_oaps_aimi_pkpd_setup_wizard_completed),
    OApsAIMIPeakGovernorEnabled(
        key = "key_aimi_peak_governor_enabled",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_peak_governor_enabled,
        summary = KeysStrings.pref_summary_aimi_peak_governor_enabled,
    ),
    /** Intelligence snapshot export in AIMI_Decisions.jsonl (intelligence_snapshot_v1). */
    OApsAIMIIntelligenceSnapshotExport("key_aimi_intelligence_snapshot_export", true, title = KeysStrings.pref_title_oaps_aimi_intelligence_snapshot_export),
    /** Single PKPD learning path per tick (signal-prep only; plugin/early are read-only). */
    OApsAIMIIntelligenceSingleLearnPath("key_aimi_intelligence_single_learn_path", true, title = KeysStrings.pref_title_oaps_aimi_intelligence_single_learn_path),
    /** TAP-D: blend profile DIA with learned structural DIA for effective runtime kinetics. */
    OApsAIMIDiaGovernorEnabled("key_aimi_dia_governor_enabled", true, title = KeysStrings.pref_title_oaps_aimi_dia_governor_enabled),
    /** InsulinActionProfiler uses prediction IOB array from kinetics authority. */
    OApsAIMIIntelligenceKineticsProfiler("key_aimi_intelligence_kinetics_profiler", true, title = KeysStrings.pref_title_oaps_aimi_intelligence_kinetics_profiler),
    /** C1 shadow: log authority vs PKPD deltas without applying to dose path. */
    OApsAIMIPredictionAuthorityShadow("key_aimi_prediction_authority_shadow", true, title = KeysStrings.pref_title_oaps_aimi_prediction_authority_shadow),
    /** C1 prod: apply DecisionPredictionAuthority to eventualBG, predBGs, stacking, SafetyNet. Default ON (harmonized
     *  in production 2026-07-12): one authoritative, physio-enriched prediction feeds the tree/Harmonia/SMB/UI/safety.
     *  Fail-safe in PredictionAuthorityApplier falls back to raw PKPD if the authority terminal is invalid. */
    OApsAIMIPredictionAuthorityEnabled(
        key = "key_aimi_prediction_authority_enabled",
        defaultValue = true,
        dependency = OApsAIMIIntelligenceSnapshotExport,
        title = KeysStrings.pref_title_oaps_aimi_prediction_authority_enabled,
    ),
    /** Meal / hyper authority retention in a *suppressed* predictive-hypo (A1 + A1b). The
     *  predictive-hypo flag means the LGS halt was SUPPRESSED (BG rising / clearly hyper), not that a
     *  hypo is imminent — so the RBT authority gate must not deny correction in that state.
     *  A1: meal-rise bypass fires on strong meal corroboration even without `safety.mealRiseConfirmed`.
     *  A1b: "clear-hyper hold" keeps SOFT (not NONE) when BG is well above the hypo threshold and not
     *  falling hard. Both only soften HARD→SOFT (never grant HARD, never bypass real hypo); all
     *  downstream vetoes (sensor, post-hypo, false-meal, protective mode) still apply. Fail-safe: false
     *  → legacy behaviour. See docs/AIMI_HARMONIA_SMB_ARBITRATION.md and undeclared-meal 3-gate analysis. */
    OApsAIMIMealHyperBypassEnabled("key_aimi_meal_hyper_bypass_enabled", true, title = KeysStrings.pref_title_oaps_aimi_meal_hyper_bypass_enabled),
    /** Tree meal-rise front-loader — lets the physiological tree's deployed `NEED_MORE_INSULIN` intent
     *  RE-OPEN a `NONE` authority that a *soft-overridable* veto (`SENSOR_LOW` / `PREDICTIVE_HYPO` /
     *  `PHYSIO_CAP`) posted, so Harmonia can apply the early SMB lift on a corroborated meal rise
     *  instead of waiting for established hyper. Restores **SOFT only** (never HARD), and only when: a
     *  real meal is corroborated (mode/causal/latent/hypothesis), BG ≥ target+45, rising (Δ≥1.2) and
     *  NOT free-falling (shared `HyperInstalledDroppingExemption` predicate). Genuine hypo vetoes stay
     *  sovereign — never overrides `PRED_MISSING` / `CHAOS_BLOCK` / `POST_HYPO_BLOCK` / real low BG.
     *  ⚠️ Overrides the sensor-confidence safety gate → **default OFF (opt-in)**. Fail-safe: false →
     *  legacy behaviour. See docs/AIMI_HARMONIA_SMB_ARBITRATION.md §8. */
    OApsAIMITreeMealRiseFrontLoad(
        key = "key_aimi_tree_meal_rise_frontload",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_tree_meal_rise_frontload,
        summary = KeysStrings.pref_summary_aimi_tree_meal_rise_frontload,
    ),
    /** CGM-first sensor confidence (root fix): base `sensor_confidence` on the CGM SOURCE (a
     *  signal-quality proxy) instead of the wearable/health-context freshness snapshot. The legacy
     *  formula weighted the watch data at 70%, so CGM trust collapsed (~0.32) without a wearable and
     *  forced Harmonia/RBT authority to NONE all day even with a perfect CGM. When on, a native/filtered
     *  CGM is trusted and a null/unknown source stays cautious (SOFT-eligible) instead of blocking.
     *  Raises dosing authority → default OFF (opt-in). Fail-safe: false → legacy wearable-weighted
     *  formula. See memory sensor-confidence-gates-harmonia. */
    OApsAIMISensorConfidenceCgmFirst(
        key = "key_aimi_sensor_confidence_cgm_first",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_sensor_confidence_cgm_first,
        summary = KeysStrings.pref_summary_aimi_sensor_confidence_cgm_first,
    ),
    /** Meal-confirmed early release (MCER) — root fix for the carb-blind dose-governing floor. On an
     *  undeclared meal (COB=0) the dose-governing terminal (`predTerminalMgdl`) is the PKPD insulin-only
     *  floor, which predicts a phantom descent while the meal/UAM path is climbing; it structurally
     *  throttles the SMB cap (tube `minPred`), holds RBT authority at SOFT and lets the PKPD safety
     *  zero the SMB — so a confirmed meal runs uncorrected for hours. When on, once a meal is
     *  corroborated (mode/causal/UAM/tree) AND rising (Δ≥1.2, not free-falling) AND BG ≥ target+20, the
     *  floor is released toward the best/UAM path (`scenarioBest.pathMin`) so the loop can dose to the
     *  configured maxima early. It only ever RAISES the floor, never lowers it, and stays bounded by
     *  maxSMB/maxSMBHB/maxBasal/maxIOB downstream. Tail circuit-breaker: reverts to the insulin-only
     *  floor as soon as the post-peak tail risk appears — absorption phase `PEAK_CORRECTION` (NOT
     *  `LATE_FAT`, which is a late rise still needing insulin), IOB headroom consumed, or the rise
     *  breaks (Δ<0) — so it cannot set up a post-peak hypo. It is also self-limiting: the release
     *  target is the best/UAM path minimum, which still contains insulin action, so stacked IOB pulls
     *  that trough (and the cap) back down automatically. Genuine hypo
     *  stays sovereign: never engages under false-meal suppression or post-hypo delivery guard.
     *  ⚠️ Raises early dosing authority on the safety floor → **default OFF (opt-in)**. Fail-safe:
     *  false → legacy insulin-only floor. See memory release-authority-channel-mutex-deadend. */
    OApsAIMIMealConfirmedEarlyRelease(
        key = "key_aimi_meal_confirmed_early_release",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_meal_confirmed_early_release,
        summary = KeysStrings.pref_summary_aimi_meal_confirmed_early_release,
    ),
    /**
     * Lever 1 — hyper-installed dropping exemption: when BG ≫ target on a meal/deep-hyper
     * plateau, do not hard-zero SMB solely because the 5‑min delta is negative
     * (`droppingFast` / `droppingFastAtHigh` / `droppingVeryFast`). Projection-gated
     * (10‑min linear must stay above hypo+buffer); freefall delta below -15 still vetoes.
     * Fail-safe: false → legacy critical-safety behaviour.
     */
    OApsAIMIHyperDroppingExemptEnabled("key_aimi_hyper_dropping_exempt_enabled", true, title = KeysStrings.pref_title_oaps_aimi_hyper_dropping_exempt_enabled),
    OApsAIMIPkpdPragmaticReliefEnabled("key_aimi_pkpd_pragmatic_relief_enabled", true, title = KeysStrings.pref_title_oaps_aimi_pkpd_pragmatic_relief_enabled),
    /** When false, AIMI stops writing loop_blackbox_v1.jsonl only; hormonitor event/daily streams unchanged. */
    OApsAIMILoopBlackboxFileEnabled("key_aimi_loop_blackbox_file_enabled", true, title = KeysStrings.pref_title_oaps_aimi_loop_blackbox_file_enabled),
    /**
     * When true, AIMI determine_basal runs under a ReentrantLock so overlapping invocations cannot corrupt state.
     * Disable only for isolated tests/benchmarks that intentionally nest calls.
     */
    OApsAIMILoopExclusiveInvocationEnabled("key_aimi_loop_exclusive_invocation", true, title = KeysStrings.pref_title_oaps_aimi_loop_exclusive_invocation_enabled),
    /**
     * When true, runs the AIMI vs OpenAPS SMB counterfactual comparator (extra SMB pass + CSV).
     * Default off to avoid cost on hot paths; enable for R&D or divergence analysis.
     */
    OApsAIMIAimiSmbComparatorEnabled("key_aimi_smb_comparator_enabled", false, title = KeysStrings.pref_title_oaps_aimi_aimi_smb_comparator_enabled),
    /** Plateau + meaningful IOB + falling prediction → throttle SMB, bias TBR, no Red Carpet restore. */
    OApsAIMIIobSurveillanceGuard("key_aimi_iob_surveillance_guard", true, title = KeysStrings.pref_title_oaps_aimi_iob_surveillance_guard),
    /** AIMI-local effective-IOB release: lets the maxIOB production gate compare against a hypo-governed partial
     *  release of the ledger→effective IOB gap (fast insulin → gate stops over-blocking corrections). Release-only,
     *  θ ≤ 0.5, retracts fully to the ledger on any hypo signal. See EffectiveIobReleaseAuthority. */
    OApsAIMIEffectiveIobReleaseEnabled("key_aimi_effective_iob_release_enabled", true, title = KeysStrings.pref_title_oaps_aimi_effective_iob_release_enabled),
    /**
     * When true, scenario projection + trajectory can lift Autodrive V3 SMB on credible hyper rise
     * (see docs/AIMI_HYPER_TRAJECTORY_RELEASE.md).
     */
    OApsAIMIHyperTrajectoryRelease(
        key = "key_aimi_hyper_trajectory_release",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_hyper_trajectory_release,
        summary = KeysStrings.pref_summary_aimi_hyper_trajectory_release,
        dependency = OApsAIMIautoDriveActive,
    ),
    OApsAIMIHyperTrajectoryReleaseAggressive(
        key = "key_aimi_hyper_trajectory_release_aggressive",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_hyper_trajectory_release_aggressive,
        summary = KeysStrings.pref_summary_aimi_hyper_trajectory_release_aggressive,
        dependency = OApsAIMIHyperTrajectoryRelease,
    ),
    /**
     * Recursive Belief Tree — export unfold to AIMI_Decisions.jsonl (shadow). See docs/AIMI_RECURSIVE_BELIEF.md.
     */
    OApsAIMIRecursiveBeliefShadow(
        key = "key_aimi_recursive_belief_shadow",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_recursive_belief_shadow,
        summary = KeysStrings.pref_summary_aimi_recursive_belief_shadow,
        dependency = OApsAIMIautoDriveActive,
    ),
    /**
     * When on (requires autodrive active), RBT [DoseChannelResolution] drives SMB floor / Traj-Bridge suppression live.
     * Independent of [OApsAIMIRecursiveBeliefShadow] (JSONL export + SHADOW_* leaves only).
     */
    OApsAIMIRecursiveBeliefAuthority(
        key = "key_aimi_recursive_belief_authority",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_recursive_belief_authority,
        summary = KeysStrings.pref_summary_aimi_recursive_belief_authority,
        dependency = OApsAIMIautoDriveActive,
    ),
    OApsAIMIRecursiveBeliefWavelet(
        key = "key_aimi_recursive_belief_wavelet",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_recursive_belief_wavelet,
        summary = KeysStrings.pref_summary_aimi_recursive_belief_wavelet,
        dependency = OApsAIMIautoDriveActive,
    ),
    /**
     * Trajectory-informed bounded tweak to DynISF (AutoISF-style CGM geometry). Requires dynamic sensitivity.
     * Default off: enable after monitoring shadow logs.
     */
    OApsAIMIDynIsfTrajectoryTuningEnabled(
        key = "aimi_dyn_isf_trajectory_tuning_enabled",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_dyn_isf_trajectory_tuning,
        summary = KeysStrings.pref_summary_aimi_dyn_isf_trajectory_tuning,
        dependency = ApsUseDynamicSensitivity,
    ),
    /**
     * When trajectory tuning is enabled: log the would-be ISF multiplier but do not apply it.
     * Default on for safe rollout; set false to apply the bounded adjustment.
     */
    OApsAIMIDynIsfTrajectoryShadowOnly(
        key = "aimi_dyn_isf_trajectory_shadow_only",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_dyn_isf_trajectory_shadow,
        summary = KeysStrings.pref_summary_aimi_dyn_isf_trajectory_shadow,
        dependency = OApsAIMIDynIsfTrajectoryTuningEnabled,
    ),
    OApsAIMIUnifiedReactivityEnabled("key_use_unified_reactivity", true, title = KeysStrings.pref_title_oaps_aimi_unified_reactivity_enabled), // 🎯 NEW: Enable UnifiedReactivityLearner
    AimiAuditorEnabled("aimi_auditor_enabled", false, title = KeysStrings.pref_title_aimi_auditor_enabled), // 🧠 AI Decision Auditor
    OApsAIMITrajectoryGuardEnabled("key_aimi_trajectory_guard_enabled", false, title = KeysStrings.pref_title_oaps_aimi_trajectory_guard_enabled), // 🌀 Phase-Space Trajectory Control
    /** Discrete tube + straight-command regularizer on max SMB (uses PKPD min-pred curve). Off by default. */
    OApsAIMIStraightLineTubeAdvisorEnabled(
        key = "key_aimi_straight_line_tube_enabled",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_straight_line_tube,
        summary = KeysStrings.pref_summary_aimi_straight_line_tube,
    ),
    OApsAIMIContextEnabled("key_aimi_context_enabled", false, title = KeysStrings.pref_title_oaps_aimi_context_enabled), // 🎯 Context Module
    // 🩸 Anti-whiplash: limit upward basal rate-of-change per tick
    OApsAIMIBasalSlewLimitEnabled(
        "key_aimi_basal_slew_limit", true,
        title = KeysStrings.pref_title_aimi_basal_slew_limit,
        summary = KeysStrings.pref_summary_aimi_basal_slew_limit,
    ),
    // 🩸 pkpd hybrid/eventual: EGP reversion off the absorbing 39 floor
    OApsAIMIPkpdEndogenousReversion(
        "key_aimi_pkpd_endo_reversion", true,
        title = KeysStrings.pref_title_aimi_pkpd_endo_reversion,
        summary = KeysStrings.pref_summary_aimi_pkpd_endo_reversion,
    ),
    /** 🩸 pkpd hyper-reversion (root fix, undeclared-meal false-hypo): when BG is clearly hyper (≥160),
     *  let the EGP reversion lift the insulin-only path off the absorbing 39 floor even while insulin is
     *  still active — otherwise, with high IOB + long learned DIA, the path never reverts and predicts a
     *  false hypo at BG ~200 (COB=0). Guard A (baseline ≤ 80 ≤ currentBG) and Guard B (falling-hard
     *  suspend) stay in force, so it never predicts a rise and never touches euglycemic/low BG.
     *  Fail-safe: false → legacy EGP behaviour. Requires [OApsAIMIPkpdEndogenousReversion]. */
    OApsAIMIPkpdHyperReversion(
        key = "key_aimi_pkpd_hyper_reversion",
        defaultValue = true,
        dependency = OApsAIMIPkpdEndogenousReversion,
        title = KeysStrings.pref_title_oaps_aimi_pkpd_hyper_reversion,
    ),
    /** 🩸 pkpd Guard B — stack-aware floor suspension (F1-B, opt-in). The hyper floor (BG ≥ 160) holds every
     *  prediction curve at ≥ 80; legacy Guard B only releases it on delta ≤ -3, ignoring IOB — so a large
     *  active stack keeps the basal whipsawing on CGM noise during a hyper descent. When ON, also suspend the
     *  floor once the stack can physiologically breach it (IOB×ISF > BG − floor) AND BG is no longer rising
     *  (delta < 0), letting the true low surface so the basal cuts and holds. Fail-safe: false → legacy
     *  delta-only Guard B. Requires [OApsAIMIPkpdHyperReversion]. */
    OApsAIMIPkpdStackAwareGuardB(
        key = "key_aimi_pkpd_stack_aware_guardb",
        defaultValue = false,
        dependency = OApsAIMIPkpdHyperReversion,
        title = KeysStrings.pref_title_oaps_aimi_pkpd_stack_aware_guard_b,
    ),
    /** 🛡️ Basal-channel safety guards (lot 3, opt-in). Two authority leaks let the automatic basal channel
     *  dose while the SMB channel was deliberately held back:
     *  1. the basal-first mutex only asks "was an SMB requested?", so an SMB **zeroed by a safety rule**
     *     (`isCriticalSafetyCondition`, `HypoRecovery` context) *unlocks* the T3C/Harmonia basal-first
     *     production channels instead of blocking them;
     *  2. when those channels own the rate they force the adaptive multiplier to 1.0, discarding the
     *     learners' protective reduction — the only damper that was still binding.
     *  When ON, a safety-zeroed SMB blocks those channels, and their rate keeps any learner reduction
     *  (`min(adaptiveMult, 1.0)`; amplifications above 1.0 are still discarded, so the rate can only be
     *  lower than today, never higher). Does not touch the manual meal modes, whose TBR stays the
     *  user-configured `DoubleKey.meal_modes_MaxBasal` setpoint.
     *  Default ON: on in production, can be turned off. */
    OApsAIMIBasalChannelSafetyGuards(
        "key_aimi_basal_channel_safety_guards", true,
        title = KeysStrings.pref_title_aimi_basal_channel_safety_guards,
        summary = KeysStrings.pref_summary_aimi_basal_channel_safety_guards,
    ),
    /** 🔒 Invariants terminaux du canal basal (lot 2, opt-in). Les protections du TBR étaient posées en
     *  amont de multiplicateurs pouvant élever le taux ×10 (`DynamicBasalController`, `AdaptiveBasal`,
     *  ampli endocrine) : un plafond placé avant un multiplicateur ne borne pas la valeur finale. Quand ON,
     *  trois invariants s'évaluent **après le dernier multiplicateur**, juste avant l'écriture du taux —
     *  prédiction sous la cible, verrou post-hypo, IOB négatif sans montée — chacun en réduction seule
     *  (`coerceAtMost` au basal profil). Les modes repas manuels sont exempts. Défaut ON : actif en production,
     *  désactivable si besoin ; chaque verdict est exporté dans `adjustments.basal_terminal`. */
    /** 🎯 Contrôleur basal en erreur projetée (lot 1). Le couple (P, D) historique combinait un gain de
     *  0,05 par mg/dL d'écart avec un gain de 1,8 par mg/dL/5min de pente — un rapport de 36:1 qui rendait
     *  le terme proportionnel incapable de freiner le terme dérivé : le moteur demandait 5 à 8× le basal
     *  profil alors que la glycémie était sous la cible. Quand ON, la glycémie est projetée sur l'horizon
     *  d'action de l'insuline et un **seul** écart est mesuré ; le gain dérivé devient `gain_P × horizon`.
     *  À glycémie stable le résultat est inchangé. Default ON: on in production. */
    OApsAIMIBasalProjectedError(
        "key_aimi_basal_projected_error", true,
        title = KeysStrings.pref_title_aimi_basal_projected_error,
        summary = KeysStrings.pref_summary_aimi_basal_projected_error,
    ),
    OApsAIMIBasalTerminalInvariants(
        "key_aimi_basal_terminal_invariants", true,
        title = KeysStrings.pref_title_aimi_basal_terminal_invariants,
        summary = KeysStrings.pref_summary_aimi_basal_terminal_invariants,
    ),
    // 🩸 pkpd predictions: shape the insulin-activity curves on the LEARNED DIA/peak, not the static profile
    OApsAIMIPkpdPredictionKinetics(
        "key_aimi_pkpd_prediction_kinetics", true,
        title = KeysStrings.pref_title_aimi_pkpd_prediction_kinetics,
        summary = KeysStrings.pref_summary_aimi_pkpd_prediction_kinetics,
    ),
    OApsAIMIContextLLMEnabled("key_aimi_context_llm_enabled", false, title = KeysStrings.pref_title_oaps_aimi_context_llm_enabled), // 🤖 LLM-powered context parsing
    OApsAIMIT3cBrittleMode("key_aimi_t3c_brittle_mode", false, title = KeysStrings.pref_title_oaps_aimi_t3c_brittle_mode),
    /**
     * T3C: fuse Autodrive V3 TBR demand into the brittle PI basal (basal-only).
     * SMB from Autodrive is stripped (optionally converted to a bounded TBR boost). Tree unlocks ceiling + ramp.
     * Depends on T3C brittle mode. Never enables pump SMB.
     */
    OApsAIMIT3cAutodriveBasalAuthority(
        key = "key_aimi_t3c_autodrive_basal_authority",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_t3c_autodrive_basal_authority,
        summary = KeysStrings.pref_summary_aimi_t3c_autodrive_basal_authority,
        dependency = OApsAIMIT3cBrittleMode,
    ),
    /**
     * T3C: hyper basal floor. When BG has stayed at/above the hyper level (160 mg/dL) for a sustained
     * window (20 min), hold the basal at the user's configured Max basal (profile max_basal) instead of
     * letting CGM noise collapse it to zero. Basal-only (TBR). Releases automatically when BG falls back
     * below the level. Depends on T3C brittle mode. On by default (opt-out) — gated behind T3C brittle mode
     * plus a sustained-dwell requirement, so a single CGM noise spike cannot trigger it.
     */
    OApsAIMIT3cHyperBasalFloor(
        key = "key_aimi_t3c_hyper_basal_floor",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_t3c_hyper_basal_floor,
        summary = KeysStrings.pref_summary_aimi_t3c_hyper_basal_floor,
        dependency = OApsAIMIT3cBrittleMode,
    ),
    /** Cystic fibrosis-related diabetes (CFRD) adaptations in T3C mode:
     *  higher LGS safety floor, COB absorption delay, exacerbation support.
     *  Default ON: T3C brittle is the intended basal-only path for CFRD; prefs are visible under T3C. */
    OApsAIMIT3cCfrdMode(
        key = "key_aimi_t3c_cfrd_mode",
        defaultValue = true,
        title = KeysStrings.pref_title_aimi_t3c_cfrd_mode,
        summary = KeysStrings.pref_summary_aimi_t3c_cfrd_mode,
        dependency = OApsAIMIT3cBrittleMode,
    ),
    /** CFRD manual exacerbation flag: raises the T3C aggressiveness ceiling during
     *  active pulmonary exacerbations or corticosteroid (steroid) treatment. */
    OApsAIMIT3cCfrdExacerbationMode(
        key = "key_aimi_t3c_cfrd_exacerbation",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_t3c_cfrd_exacerbation,
        summary = KeysStrings.pref_summary_aimi_t3c_cfrd_exacerbation,
        dependency = OApsAIMIT3cCfrdMode,
    ),
    /** Undeclared-meal COB estimation: derives a bounded virtual COB (grams) from the glucose
     *  appearance rate, BG dynamics, weight/TDD ceiling and rest/activity context, then injects it
     *  into the prediction path so basal (TBR) anticipates a meal that was not declared. TBR-only:
     *  it never adds autonomous SMB, and is muted by exercise / hypo / exacerbation / false-meal
     *  suppression. Off by default. */
    OApsAIMIUndeclaredCobEnabled(
        "key_aimi_undeclared_cob_enabled", false,
        title = KeysStrings.pref_title_aimi_undeclared_cob,
        summary = KeysStrings.pref_summary_aimi_undeclared_cob,
    ),
    // 🦋 Thyroid / Basedow Module (MTR)
    OApsAIMIThyroidEnabled("key_aimi_thyroid_enabled", false, title = KeysStrings.pref_title_oaps_aimi_thyroid_enabled),
    OApsAIMIThyroidLogVerbosity("key_aimi_thyroid_debug", false, title = KeysStrings.pref_title_oaps_aimi_thyroid_log_verbosity),
    // 🏥 AIMI Physiological Assistant (MTR) — vitals multipliers / assistant extras only.
    // Does NOT gate PhysiologicalTree / Harmonia (cascade native always-on since 2026-07-18).
    AimiPhysioAssistantEnable("aimi_physio_assistant_enable", false, title = KeysStrings.pref_title_aimi_physio_assistant_enable),
    AimiPhysioSleepDataEnable("aimi_physio_sleep_enable", true, title = KeysStrings.pref_title_aimi_physio_sleep_data_enable),
    AimiPhysioHRVDataEnable("aimi_physio_hrv_enable", true, title = KeysStrings.pref_title_aimi_physio_hrv_data_enable),
    AimiPhysioLLMAnalysisEnable("aimi_physio_llm_enable", false, title = KeysStrings.pref_title_aimi_physio_llm_analysis_enable),
    AimiPhysioDebugLogs("aimi_physio_debug_logs", false, title = KeysStrings.pref_title_aimi_physio_debug_logs),
    // 🌸 Endometriosis & Cycle Management (MTR)
    AimiEndometriosisEnable("aimi_endo_enable", false, title = KeysStrings.pref_title_aimi_endometriosis_enable),
    AimiEndometriosisHormonalSuppression("aimi_endo_suppression", false, title = KeysStrings.pref_title_aimi_endometriosis_hormonal_suppression),
    AimiEndometriosisPainFlare("aimi_endo_flare", false, title = KeysStrings.pref_title_aimi_endometriosis_pain_flare),
    OApsAIMIMealAdvisorTrigger("aimi_meal_advisor_trigger", false, title = KeysStrings.pref_title_oaps_aimi_meal_advisor_trigger), // Trigger for one-shot MAX-SMB bypass
    // 🌀 Adaptive Kernel Bank (Cosine Gate)
    AimiCosineGateEnabled("aimi_cosine_gate_enabled", true, title = KeysStrings.pref_title_aimi_cosine_gate_enabled),
    // Emergency SOS (Hypo) — SMS-only advanced manager
    AimiEmergencySosEnable(
        key = "aimi_emergency_sos_enable",
        defaultValue = false,
        title = KeysStrings.pref_title_aimi_sos_enable,
        summary = KeysStrings.pref_summary_aimi_sos_enable,
    ),
    /** On-device MLP risk models for AIMI Advisor (OREF features); trains when Advisor runs if enough rows. */
    OApsAIMIAdvisorPersonalOrefMl(
        "key_aimi_advisor_personal_oref_ml",
        false,
        KeysStrings.pref_title_aimi_advisor_personal_oref_ml,
        KeysStrings.pref_summary_aimi_advisor_personal_oref_ml,
    ),
    /** When true, AI Coach prompt includes structured user-insight block from OREF (easier plain-language coaching). */
    OApsAIMIAdvisorLlmRichOref(
        "key_aimi_advisor_llm_rich_oref",
        true,
        KeysStrings.pref_title_aimi_advisor_llm_rich_oref,
        KeysStrings.pref_summary_aimi_advisor_llm_rich_oref,
    ),
    /** Transient Preference Overlay — temporary protection prefs (45 min). */
    OApsAIMITpoEnabled(
        "key_aimi_tpo_enabled",
        true,
        KeysStrings.pref_title_aimi_tpo_enabled,
        KeysStrings.pref_summary_aimi_tpo_enabled,
    ),
    /** Require LLM confirmation before TPO apply (when API key available). */
    OApsAIMITpoLlmConfirmEnabled(
        "key_aimi_tpo_llm_confirm_enabled",
        true,
        KeysStrings.pref_title_aimi_tpo_llm_confirm_enabled,
        KeysStrings.pref_summary_aimi_tpo_llm_confirm_enabled,
        dependency = OApsAIMITpoEnabled,
    ),
    /** Show notification when a TPO session starts (reserved for notification UX). */
    OApsAIMITpoNotifyOnApply(
        "key_aimi_tpo_notify_on_apply",
        true,
        KeysStrings.pref_title_aimi_tpo_notify_on_apply,
        KeysStrings.pref_summary_aimi_tpo_notify_on_apply,
        dependency = OApsAIMITpoEnabled,
    ),
    OApsxdriponeminute(key = "key_use_Aimi_xdripOM", defaultValue = false, title = KeysStrings.pref_title_oapsxdriponeminute),

    ;

}
