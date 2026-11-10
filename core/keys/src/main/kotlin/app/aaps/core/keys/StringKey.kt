package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceEnabledCondition
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.StringValidator
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec

enum class StringKey(
    override val key: String,
    override val defaultValue: String,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val entries: Map<String, Int> = emptyMap(),
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val isHashed: Boolean = false,
    override val exportable: Boolean = true,
    override val validator: StringValidator = StringValidator.NONE,
    override val visibility: ElementVisibility = ElementVisibility.ALWAYS,
    override val enabledCondition: PreferenceEnabledCondition = PreferenceEnabledCondition.ALWAYS,
    override val sync: SyncSpec? = null
) : StringPreferenceKey {

    GeneralUnits(
        key = "units",
        defaultValue = "mg/dl",
        titleResId = R.string.pref_title_units,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "mg/dl" to R.string.units_mgdl,
            "mmol" to R.string.units_mmol
        ),
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    GeneralLanguage(
        key = "language",
        defaultValue = "default",
        titleResId = R.string.pref_title_language,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "default" to R.string.lang_default,
            "en" to R.string.lang_en,
            "af" to R.string.lang_af,
            "bg" to R.string.lang_bg,
            "cs" to R.string.lang_cs,
            "de" to R.string.lang_de,
            "dk" to R.string.lang_dk,
            "fr" to R.string.lang_fr,
            "nl" to R.string.lang_nl,
            "es" to R.string.lang_es,
            "el" to R.string.lang_el,
            "ga" to R.string.lang_ga,
            "it" to R.string.lang_it,
            "ko" to R.string.lang_ko,
            "lt" to R.string.lang_lt,
            "nb" to R.string.lang_nb,
            "pl" to R.string.lang_pl,
            "pt" to R.string.lang_pt,
            "pt_BR" to R.string.lang_pt_br,
            "ro" to R.string.lang_ro,
            "ru" to R.string.lang_ru,
            "sk" to R.string.lang_sk,
            "sv" to R.string.lang_sv,
            "tr" to R.string.lang_tr,
            "zh_TW" to R.string.lang_zh_tw,
            "zh_CN" to R.string.lang_zh_cn
        ),
        defaultedBySM = true
    ),
    GeneralPatientName(
        key = "patient_name",
        defaultValue = "",
        titleResId = R.string.pref_title_patient_name,
        summaryResId = R.string.pref_summary_patient_name,
        validator = StringValidator.personName()
    ),
    GeneralDarkMode(
        key = "use_dark_mode",
        defaultValue = "dark",
        titleResId = R.string.pref_title_app_color_scheme,
        summaryResId = R.string.pref_summary_theme_switcher,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "dark" to R.string.pref_dark_theme,
            "light" to R.string.pref_light_theme,
            "system" to R.string.pref_follow_system_theme
        ),
        defaultedBySM = true
    ),

    /**
     * Selected skin implementation (fully qualified class name).
     * Used by Compose home routing and SkinProvider.
     */
    GeneralSkin(
        key = "skin",
        defaultValue = "",
        titleResId = R.string.pref_title_skin,
        preferenceType = PreferenceType.LIST,
    ),

    AapsDirectoryUri(key = "aaps_directory", defaultValue = "", titleResId = R.string.pref_title_aaps_directory),

    ProtectionMasterPassword(key = "master_password", defaultValue = "", titleResId = R.string.pref_title_master_password, isPassword = true, isHashed = true),
    ProtectionSettingsPassword(
        key = "settings_password", defaultValue = "", titleResId = R.string.pref_title_settings_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeSettings }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionSettingsPin(
        key = "settings_pin", defaultValue = "", titleResId = R.string.pref_title_settings_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeSettings }, ProtectionType.CUSTOM_PIN.ordinal)
    ),
    ProtectionApplicationPassword(
        key = "application_password", defaultValue = "", titleResId = R.string.pref_title_application_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeApplication }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionApplicationPin(
        key = "application_pin", defaultValue = "", titleResId = R.string.pref_title_application_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeApplication }, ProtectionType.CUSTOM_PIN.ordinal)
    ),
    ProtectionBolusPassword(
        key = "bolus_password", defaultValue = "", titleResId = R.string.pref_title_bolus_password, isPassword = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeBolus }, ProtectionType.CUSTOM_PASSWORD.ordinal)
    ),
    ProtectionBolusPin(
        key = "bolus_pin", defaultValue = "", titleResId = R.string.pref_title_bolus_pin, isPin = true, isHashed = true,
        visibility = ElementVisibility.intEquals({ IntKey.ProtectionTypeBolus }, ProtectionType.CUSTOM_PIN.ordinal)
    ),

    SafetyAge(key = "age", defaultValue = "adult", titleResId = R.string.pref_title_patient_age, preferenceType = PreferenceType.LIST, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    MaintenanceEmail(
        key = "maintenance_logs_email",
        defaultValue = "logs@aaps.app",
        titleResId = R.string.maintenance_email,
        defaultedBySM = true,
        validator = StringValidator.email()
    ),
    MaintenanceIdentification(key = "email_for_crash_report", defaultValue = "", titleResId = R.string.pref_title_identification),
    AutomationLocation(
        key = "location",
        defaultValue = "PASSIVE",
        titleResId = R.string.pref_title_automation_location,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "PASSIVE" to R.string.automation_location_passive,
            "NETWORK" to R.string.automation_location_network,
            "GPS" to R.string.automation_location_gps
        ),
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    SmsAllowedNumbers(
        key = "smscommunicator_allowednumbers",
        defaultValue = "",
        titleResId = R.string.smscommunicator_allowednumbers,
        summaryResId = R.string.smscommunicator_allowednumbers_summary,
        validator = StringValidator.multiPhone()
    ),
    SmsOtpPassword(
        key = "smscommunicator_otp_password",
        defaultValue = "",
        titleResId = R.string.smscommunicator_otp_pin,
        summaryResId = R.string.smscommunicator_otp_pin_summary,
        dependency = BooleanKey.SmsAllowRemoteCommands,
        isPassword = true,
        validator = StringValidator.pinStrength()
    ),
    VirtualPumpType(key = "virtualpump_type", defaultValue = "Generic AAPS", titleResId = R.string.pref_title_virtual_pump_type, preferenceType = PreferenceType.LIST),

    NsClientUrl(
        key = "nsclientinternal_url",
        defaultValue = "",
        titleResId = R.string.ns_client_url_title,
        summaryResId = R.string.ns_client_url_summary,
        validator = StringValidator.httpsUrl()
    ),
    NsClientApiSecret(
        key = "nsclientinternal_api_secret",
        defaultValue = "",
        titleResId = R.string.ns_client_secret_title,
        summaryResId = R.string.ns_client_secret_summary,
        isPassword = true,
        validator = StringValidator.minLength(12)
    ),
    NsClientWifiSsids(
        key = "ns_wifi_ssids",
        defaultValue = "",
        titleResId = R.string.ns_wifi_ssids,
        summaryResId = R.string.ns_wifi_ssids_summary,
        dependency = BooleanKey.NsClientUseWifi
    ),
    NsClientAccessToken(
        key = "nsclient_token",
        defaultValue = "",
        titleResId = R.string.nsclient_token_title,
        summaryResId = R.string.nsclient_token_summary,
        isPassword = true,
        validator = StringValidator.minLength(17)
    ),

    PumpCommonBolusStorage("pump_sync_storage_bolus", ""),
    PumpCommonTbrStorage("pump_sync_storage_tbr", ""),
    GarminRequestKey(key = "garmin_aaps_key", defaultValue = ""),
    OApsAIMIWCycleTrackingMode("key_oaps_aimi_wcycle_tracking_mode", "FIXED_28", preferenceType = PreferenceType.LIST),
    OApsAIMIWCycleContraceptive("key_oaps_aimi_wcycle_contraceptive", "NONE", preferenceType = PreferenceType.LIST),
    OApsAIMIWCycleThyroid("key_oaps_aimi_wcycle_thyroid", "EUTHYROID", preferenceType = PreferenceType.LIST),
    OApsAIMIWCycleVerneuil("key_oaps_aimi_wcycle_verneuil", "NONE", preferenceType = PreferenceType.LIST),
    OApsAIMINightGrowthStart("key_oaps_aimi_ngr_night_start", "22:00"),
    OApsAIMINightGrowthEnd("key_oaps_aimi_ngr_night_end", "06:00"),
    AimiAdvisorOpenAIKey("aimi_advisor_openai_key", "", isPassword = true),
    AimiAdvisorGeminiKey("aimi_advisor_gemini_key", "", isPassword = true),
    AimiAdvisorDeepSeekKey("aimi_advisor_deepseek_key", "", isPassword = true),
    AimiAdvisorClaudeKey("aimi_advisor_claude_key", "", isPassword = true),
    AimiAdvisorProvider("aimi_advisor_provider", "OPENAI", preferenceType = PreferenceType.LIST),
    /** Last tuning context selected in AIMI Profile Advisor (AimiTuningContext enum name). */
    AimiTuningContextSelection("aimi_tuning_context_selection", "AUTO_BALANCE"),
    AimiAuditorMode("aimi_auditor_mode", "AUDIT_ONLY", preferenceType = PreferenceType.LIST),  // resolved via withEntries in plugin

    // Context Module (dedicated provider for flexibility)
    ContextLLMProvider("aimi_context_llm_provider", "OPENAI"),
    ContextLLMOpenAIKey("aimi_context_llm_openai_key", "", isPassword = true),
    ContextLLMGeminiKey("aimi_context_llm_gemini_key", "", isPassword = true),
    ContextLLMDeepSeekKey("aimi_context_llm_deepseek_key", "", isPassword = true),
    ContextLLMClaudeKey("aimi_context_llm_claude_key", "", isPassword = true),
    ContextMode("aimi_context_mode", "BALANCED"), // CONSERVATIVE, BALANCED, AGGRESSIVE

    OApsAIMIUnstableModeState("key_oaps_aimi_mode_state", ""),
    OApsAIMIContextStorage("aimi_context_storage", "", exportable = false),

    // 🏥 AIMI Physiological Assistant (MTR)
    AimiPhysioLLMProvider("aimi_physio_llm_provider", "gpt4"),

    // 🦋 Thyroid / Basedow Module (MTR)
    OApsAIMIThyroidMode("key_aimi_thyroid_mode", "MANUAL", preferenceType = PreferenceType.LIST),
    OApsAIMIThyroidManualStatus("key_aimi_thyroid_manual_status", "EUTHYROID", preferenceType = PreferenceType.LIST),
    OApsAIMIThyroidTreatmentPhase("key_aimi_thyroid_treatment_phase", "NONE", preferenceType = PreferenceType.LIST),
    OApsAIMIThyroidGuardLevel("key_aimi_thyroid_guard_level", "HIGH", preferenceType = PreferenceType.LIST),

    OverviewVicoBgReadingTint(
        key = "overview_vico_bg_reading_tint",
        defaultValue = "theme",
        titleResId = R.string.pref_title_vico_bg_reading_tint,
        summaryResId = R.string.pref_summary_vico_bg_reading_tint,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "theme" to R.string.pref_vico_tint_theme,
            "mauve" to R.string.pref_vico_tint_mauve,
            "ocean" to R.string.pref_vico_tint_ocean,
            "amber" to R.string.pref_vico_tint_amber,
            "rose" to R.string.pref_vico_tint_rose,
        ),
    ),
    OverviewVicoChartBackdrop(
        key = "overview_vico_chart_backdrop",
        defaultValue = "theme",
        titleResId = R.string.pref_title_vico_chart_backdrop,
        summaryResId = R.string.pref_summary_vico_chart_backdrop,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "theme" to R.string.pref_vico_backdrop_theme,
            "deep" to R.string.pref_vico_backdrop_deep,
            "warm" to R.string.pref_vico_backdrop_warm,
            "cool" to R.string.pref_vico_backdrop_cool,
            "muted" to R.string.pref_vico_backdrop_muted,
        ),
    ),

    // Emergency SOS (Hypo) — SMS-only advanced manager
    AimiEmergencySosPhone(
        key = "aimi_emergency_sos_phone",
        defaultValue = "",
        titleResId = R.string.pref_title_aimi_sos_phone,
        summaryResId = R.string.pref_summary_aimi_sos_phone,
        dependency = BooleanKey.AimiEmergencySosEnable,
    ),
    AimiEmergencySosPhone2(
        key = "aimi_emergency_sos_phone2",
        defaultValue = "",
        titleResId = R.string.pref_title_aimi_sos_phone2,
        summaryResId = R.string.pref_summary_aimi_sos_phone2,
        dependency = BooleanKey.AimiEmergencySosEnable,
    ),

    /** Sound of the low-glucose alarms (hypo, rapid fall): the AAPS sound or a soft generated melody. */
    AlertSoundLowGlucose(
        key = "alert_sound_low_glucose",
        defaultValue = "default",
        titleResId = R.string.pref_title_alert_sound_low_glucose,
        summaryResId = R.string.pref_summary_alert_sound,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "default" to R.string.alert_sound_default,
            "soft_chime" to R.string.alert_sound_soft_chime,
            "warm_bells" to R.string.alert_sound_warm_bells,
            "marimba" to R.string.alert_sound_marimba,
            "gentle_pulse" to R.string.alert_sound_gentle_pulse,
        ),
    ),

    /** Sound of every other urgent alarm (pump, missed readings, …). Same choices. */
    AlertSoundOtherAlarms(
        key = "alert_sound_other_alarms",
        defaultValue = "default",
        titleResId = R.string.pref_title_alert_sound_other_alarms,
        summaryResId = R.string.pref_summary_alert_sound,
        preferenceType = PreferenceType.LIST,
        entries = mapOf(
            "default" to R.string.alert_sound_default,
            "soft_chime" to R.string.alert_sound_soft_chime,
            "warm_bells" to R.string.alert_sound_warm_bells,
            "marimba" to R.string.alert_sound_marimba,
            "gentle_pulse" to R.string.alert_sound_gentle_pulse,
        ),
    ),
}
