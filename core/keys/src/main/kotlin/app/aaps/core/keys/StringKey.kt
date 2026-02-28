package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey

enum class StringKey(
    override val key: String,
    override val defaultValue: String,
    override val defaultedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val isPassword: Boolean = false,
    override val isPin: Boolean = false,
    override val exportable: Boolean = true
) : StringPreferenceKey {

    GeneralUnits("units", "mg/dl"),
    GeneralLanguage("language", "default", defaultedBySM = true),
    GeneralPatientName("patient_name", ""),
    GeneralSkin("skin", ""),
    GeneralDarkMode("use_dark_mode", "dark", defaultedBySM = true),

    AapsDirectoryUri("aaps_directory", ""),

    ProtectionMasterPassword("master_password", "", isPassword = true),
    ProtectionSettingsPassword("settings_password", "", isPassword = true),
    ProtectionSettingsPin("settings_pin", "", isPin = true),
    ProtectionApplicationPassword("application_password", "", isPassword = true),
    ProtectionApplicationPin("application_pin", "", isPin = true),
    ProtectionBolusPassword("bolus_password", "", isPassword = true),
    ProtectionBolusPin("bolus_pin", "", isPin = true),

    OverviewCopySettingsFromNs(key = "statuslights_copy_ns", "", dependency = BooleanKey.OverviewShowStatusLights),

    SafetyAge("age", "adult"),
    MaintenanceEmail("maintenance_logs_email", "logs@aaps.app", defaultedBySM = true),
    MaintenanceIdentification("email_for_crash_report", ""),
    AutomationLocation("location", "PASSIVE", hideParentScreenIfHidden = true),

    SmsAllowedNumbers("smscommunicator_allowednumbers", ""),
    SmsOtpPassword("smscommunicator_otp_password", "", dependency = BooleanKey.SmsAllowRemoteCommands, isPassword = true),
    VirtualPumpType("virtualpump_type", "Generic AAPS"),

    NsClientUrl("nsclientinternal_url", ""),
    NsClientApiSecret("nsclientinternal_api_secret", "", isPassword = true),
    NsClientWifiSsids("ns_wifi_ssids", "", dependency = BooleanKey.NsClientUseWifi),
    NsClientAccessToken("nsclient_token", "", isPassword = true),

    PumpCommonBolusStorage("pump_sync_storage_bolus", ""),
    PumpCommonTbrStorage("pump_sync_storage_tbr", ""),
    GarminRequestKey(key = "garmin_aaps_key", defaultValue = ""),
    OApsAIMIWCycleTrackingMode("key_oaps_aimi_wcycle_tracking_mode", "FIXED_28"),
    OApsAIMIWCycleContraceptive("key_oaps_aimi_wcycle_contraceptive", "NONE"),
    OApsAIMIWCycleThyroid("key_oaps_aimi_wcycle_thyroid", "EUTHYROID"),
    OApsAIMIWCycleVerneuil("key_oaps_aimi_wcycle_verneuil", "NONE"),
    OApsAIMINightGrowthStart("key_oaps_aimi_ngr_night_start", "22:00"),
    OApsAIMINightGrowthEnd("key_oaps_aimi_ngr_night_end", "06:00"),
    AimiAdvisorOpenAIKey("aimi_advisor_openai_key", "", isPassword = true),
    AimiAdvisorGeminiKey("aimi_advisor_gemini_key", "", isPassword = true),
    AimiAdvisorDeepSeekKey("aimi_advisor_deepseek_key", "", isPassword = true),
    AimiAdvisorClaudeKey("aimi_advisor_claude_key", "", isPassword = true),
    AimiAdvisorProvider("aimi_advisor_provider", "OPENAI"),
    AimiAuditorMode("aimi_auditor_mode", "AUDIT_ONLY"),  // 🧠 AI Auditor mode: AUDIT_ONLY, SOFT_MODULATION, HIGH_RISK_ONLY

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
    OApsAIMIThyroidMode("key_aimi_thyroid_mode", "MANUAL"),
    OApsAIMIThyroidManualStatus("key_aimi_thyroid_manual_status", "EUTHYROID"),
    OApsAIMIThyroidTreatmentPhase("key_aimi_thyroid_treatment_phase", "NONE"),
    OApsAIMIThyroidGuardLevel("key_aimi_thyroid_guard_level", "HIGH"),
    
    // 🚨 Emergency SOS (Hypo)
    AimiEmergencySosPhone("aimi_emergency_sos_phone", ""),
}
