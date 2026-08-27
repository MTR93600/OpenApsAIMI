package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class BooleanNonKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val exportable: Boolean = true
) : BooleanNonPreferenceKey {

    GeneralSetupWizardProcessed("startupwizard_processed", false),
    SetupWizardIUnderstand("I_understand", false),
    ObjectivesLoopUsed("ObjectivesLoopUsed", false),
    ObjectivesScaleUsed("ObjectivesScaleUsed", false),
    ObjectivesPumpStatusIsAvailableInNS("ObjectivespumpStatusIsAvailableInNS", false),
    ObjectivesBgIsAvailableInNs("ObjectivesbgIsAvailableInNS", false),
    ObjectivesProfileSwitchUsed("ObjectivesProfileSwitchUsed", false),
    ObjectivesDisconnectUsed("ObjectivesDisconnectUsed", false),
    ObjectivesReconnectUsed("ObjectivesReconnectUsed", false),
    ObjectivesTempTargetUsed("ObjectivesTempTargetUsed", false),
    AutosensUsedOnMainPhone("used_autosens_on_main_phone", false),

    // Wizard toggle states (persisted across restarts, not user-facing preferences)
    WizardIncludeCob("wizard_include_cob", false),
    WizardIncludeTrend("wizard_include_trend_bg", false),

    // Export destination settings (managed by ExportOptionsDialog, no preferences UI)
    ExportAllCloudEnabled("export_all_cloud_enabled", false),
    ExportLogEmailEnabled("export_log_email_enabled", true),
    ExportLogCloudEnabled("export_log_cloud_enabled", false),
    ExportSettingsLocalEnabled("export_settings_local_enabled", true),
    ExportSettingsCloudEnabled("export_settings_cloud_enabled", false),
    ExportCsvLocalEnabled("export_csv_local_enabled", true),
    ExportCsvCloudEnabled("export_csv_cloud_enabled", false),

    AllowHardwarePump("allow_hardware_pump", false),

    // Statistics screen section expanded/collapsed states (per-device UI state, not user-facing preferences)
    StatsTddExpanded("stats_tdd_expanded", true, exportable = false),
    StatsTirExpanded("stats_tir_expanded", true, exportable = false),
    StatsDexcomTirExpanded("stats_dexcom_tir_expanded", true, exportable = false),
    StatsActivityExpanded("stats_activity_expanded", true, exportable = false),
    StatsTddCycleExpanded("stats_tdd_cycle_expanded", false, exportable = false),

    // Set (committed) right before a startup VACUUM and cleared after it finishes. If it is still
    // set on the next launch, the previous VACUUM died below the JVM (native abort / OOM) — used to
    // break the boot-crash loop. Transient device state, not a user setting → not exportable.
    VacuumInProgress("vacuum_in_progress", false, exportable = false),
    // AIMI keys ported from freeze aimi-baseline-2026-08-26
    /**
     * One-shot: after AIMI endocrine Lot A upgrade, re-enable T3C adaptive basal if Stability/HYPO_GUARD
     * writeback had forced it off. User may disable again afterward.
     */
    AimiAdaptiveBasalReenabledOnUpgrade("aimi_adaptive_basal_reenabled_on_upgrade", false, exportable = false),

}