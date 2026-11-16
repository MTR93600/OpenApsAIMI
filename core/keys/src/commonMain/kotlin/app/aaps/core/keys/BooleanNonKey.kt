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

    // Set (committed) right before a startup VACUUM and cleared after it finishes. If it is still
    // set on the next launch, the previous VACUUM died below the JVM (native abort / OOM) — used to
    // break the boot-crash loop. Transient device state, not a user setting → not exportable.
    VacuumInProgress("vacuum_in_progress", false, exportable = false),

    // Client: the pairing secret has been replaced by the key exchange in the hello. Until then the client sends a hello
    // with a key on each connection, which is also how a phone paired from a QR code says hello at all. Belongs to the
    // pairing.
    NsClientControlSecretReplaced("nsclient_control_secret_replaced", false, exportable = false),

    /**
     * One-shot: on first launch after the Glass top-grid pills (pump/cannula/battery/sensor/loop/activity)
     * became personalizable, merges their defaults into any already-persisted [StringNonKey.GlassSelectedPills]
     * value. Installs that customized their pill selection before would otherwise never see the new pills
     * (their raw value predates those IDs). No-op for a fresh install (its value already has them from the
     * class default). The Glass home runs this migration with `withTopGridDefaultsMerged`.
     */
    GlassTopGridPillsMigrated("glass_top_grid_pills_migrated", false, exportable = false),

    /**
     * One-shot: set after the AIMI PK/PD learning pace migration ran once. The old PK/PD wizard always
     * saved the NORMAL pace (0.5 h/day, 5 min/day). The migration moves exactly that stored pair to
     * the FASTER default (2 h/day, 20 min/day) and keeps any other stored pace. Bookkeeping, not a
     * setting.
     *
     * Exported on purpose (owner decision, step D1 of the AIMI settings plan): a backup made after the
     * migration carries it, so restoring that backup on a new phone or a fresh install keeps a NORMAL
     * pace the user picked on purpose. A backup made before the update does not carry it: on a new
     * phone its old wizard pace 0.5/5.0 is moved to FASTER once, as intended. An import never clears
     * the flag, so restoring an old backup on a phone that already ran the migration keeps its pace.
     */
    OApsAIMIPkpdPaceMigrated("aimi_pkpd_pace_migrated", false),

    /**
     * One-shot: set after the AIMI meal factor repair ran once (step T2, see
     * `AimiMealFactorScaleRepair`). Bookkeeping, not a setting. Exported on purpose: a backup made
     * after the repair carries it, so importing that backup keeps a low meal factor the user set by
     * hand. A backup made before the repair does not carry it, so its broken values are repaired on
     * import.
     */
    OApsAIMIMealFactorScaleRepaired("aimi_meal_factor_scale_repaired", false),

    /**
     * Set after the guided AIMI PK/PD setup wizard finished or was skipped. Bookkeeping, not a
     * setting: it was a [BooleanKey] until step P3 and keeps its stored name, so the wizard does not
     * come back after the update. Exported, so a restored backup does not show the wizard again.
     */
    OApsAIMIPkpdSetupWizardCompleted("key_aimi_pkpd_setup_wizard_completed", false),

    /**
     * Shows the IOB chart under the glucose chart on the Glass home (Tools → Personalize). A small phone
     * can turn it off so the home fits on the screen without scrolling. The IOB stays in the status card.
     */
    GlassShowIobChart("glass_show_iob_chart", true, exportable = false),

    /**
     * The user stopped AAPS on this phone (Maintenance → Stop AAPS), for example to hand the pump and
     * the sensor to another phone. While set, no pump command is sent, the loop does not run and the
     * sensor drivers are stopped. Kept across restarts until the user resumes. Device state, so never
     * exported: a stopped phone must not stop the phone that imports its settings.
     */
    AapsStoppedByUser("aaps_stopped_by_user", false, exportable = false),
}