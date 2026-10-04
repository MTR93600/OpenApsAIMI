package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey

/**
 * A screen, or one level of a screen, that really shows AIMI settings.
 *
 * A section names the content that draws it. [AimiSettingsScreens.routedContents] then says
 * which of those contents the navigation actually reaches, and that is the whole point: the
 * `PkpdExpert` content existed for months while nothing routed to it, so three ISF fusion keys
 * were stored but unreachable.
 */
internal enum class AimiSettingsContentId {
    PkpdSimple,
    PkpdAdvanced,
    PkpdExpert,
    ControlCenter,
    PreferencesRoot,
    PreferencesAiKeys,
    PreferencesTpo,
    PreferencesPhysio,
    PreferencesPatientContext,
    PreferencesWomenCycle,
    PreferencesInflammatory,
    PreferencesThyroid,
    PreferencesNightGrowth,
    PreferencesLab,
    PreferencesAdaptiveBasal,
    PreferencesT3c,
    PreferencesTrajectory,
    PreferencesManualModes,
    PreferencesAutodrive,
    PreferencesAutodrivePrebolusVars,
    PreferencesAiAuditor,
    ContextEditor,
    PreferencesEmergencySos,

    /**
     * The settings audit screen. Unlike every other content it declares no section and no list of
     * keys: it reads [AimiSettingsManifest.entries] directly and shows the ones whose stored value
     * differs from their shipped default. It is opened from the plugin by its own intent key, not
     * through the sections below, so it is deliberately absent from `alwaysRoutedContents` — listing
     * it there would have claimed a route the sections do not actually provide. See
     * `AimiSettingsAudit` and `AimiSettingsAuditScreen`.
     */
    SettingsAudit,
}

/** How a section shows its keys. */
internal enum class AimiSettingsSectionKind {

    /** One editable item per key. Every key must carry the level of the section. */
    FIELDS,

    /** One control (a slider, a chip row, an editor) that drives the listed keys. */
    CONTROL,
}

/** Stable name of a section, so a test can point at one. */
internal enum class AimiSettingsSectionId {
    PkpdSimpleEnable,
    PkpdSimpleInsulinPreset,
    PkpdSimpleCorrectionPrudence,
    PkpdSimpleTailPrudence,
    PkpdAdvancedLearningPace,
    PkpdAdvancedStartingKinetics,
    PkpdAdvancedCustomBounds,
    PkpdAdvancedStackAwareGuardB,
    PkpdExpertPeakGovernor,
    PkpdExpertIsfFusionBounds,
    PkpdExpertIsfFusionSlope,
    PkpdExpertDynIsfTuning,
    PkpdExpertDynIsfShadow,
    PkpdExpertDynIsfFraction,
    PkpdExpertSmbTailThreshold,
    PkpdExpertSmbDampings,
    PkpdExpertReliefEnable,
    PkpdExpertReliefFactors,
    PkpdExpertIobSurveillance,
    PkpdExpertPriorityMaxIob,
    ControlCenterProtectionLevel,
    ControlCenterMealCaptureLevel,
    ControlCenterStabilityLevel,
    ControlCenterAutonomyMode,
    PreferencesRootRemotePin,
    PreferencesAiAdvisorLearning,
    TpoEnable,
    TpoOptions,
    PhysioSources,
    PatientBody,
    PatientPregnancyDueDate,
    PatientHoneymoon,
    PatientNightMode,
    WomenCycleIntent,
    WomenCycleExpert,
    InflammatoryContext,
    ThyroidModule,
    ThyroidDebug,
    NightGrowthIntent,
    NightGrowthBudget,
    LabMlTraining,
    LabDoseLimits,
    LabUnifiedReactivity,
    AdaptiveBasalEnable,
    AdaptiveBasalExpert,
    T3cMode,
    T3cAutodriveAuthority,
    T3cBehaviour,
    T3cCfrdLgsFloor,
    T3cCfrdCobDelay,
    T3cAnticipation,
    TrajectoryGuards,
    ModeBreakfast,
    ManualModesMaxBasal,
    ModeLunch,
    ModeDinner,
    ModeHighCarb,
    ModeSnack,
    ModeMeal,
    ModeSleep,
    AutodriveChannel,
    AutodriveBeliefShadow,
    AutodriveBeliefAuthority,
    AutodriveBeliefWavelet,
    AutodriveMealRise,
    AutodriveSensorConfidence,
    AutodriveGestures,
    AutodriveMaxBasal,
    AutodrivePrebolusVariables,
    AuditorProfileFactors,
    ContextModuleEnable,
    ContextIntentStore,
    // --- Widened scope batch (2026-10-03) ---
    LabXdripOneMinute,
    AdaptiveBasalLimits,
    PhysioModuleToggles,
    PhysioAiAnalysis,
    EndoStatus,
    EndoTuning,
    TubeAdvancedTunables,
    AuditorSettings,
    AiAdvisorKeys,
    SosEnable,
    SosThresholds,
    SosContacts,
}

/**
 * One group of keys as a screen shows it, in screen order.
 *
 * Two facts that look alike but are not:
 * - [content] is **where** the group is drawn. It decides whether the group is reachable.
 * - [level] is **what** the group offers: the level of the keys in it. It decides which keys may
 *   be in it, and a [FIELDS][AimiSettingsSectionKind.FIELDS] group must be level clean.
 *
 * So a group declared `EXPERT` may well be drawn inside the simple tab. That is exactly what the
 * PK/PD insulin preset chips are: a simple control that writes expert kinetics keys.
 */
internal data class AimiSettingsSection(
    val id: AimiSettingsSectionId,
    val content: AimiSettingsContentId,
    val level: AimiSettingsLevel,
    val kind: AimiSettingsSectionKind,
    val keys: List<NonPreferenceKey>,
)

/**
 * Where every AIMI setting is shown.
 *
 * Some screens read their key list from here directly, through
 * [AimiSettingsScreens.preferenceKeysOf] — for those, a section can never drift from what is
 * really drawn, because it IS what is drawn. The three PK/PD contents and a handful of
 * `OpenAPSAIMIPlugin.kt` sub-screens (TPO, adaptive basal, T3C, the meal mode screens) work this
 * way.
 *
 * Most screens still hold their own hand written `items = buildList { add(...) }`, because that
 * list interleaves AIMI keys with non-AIMI keys, `withEntries(...)` wrappers and `ApsIntentKey`
 * items that this manifest has no room for. For those, a section here is a **declared mirror**:
 * it must list the same AIMI keys, in the same screen, by hand. If you add, remove or move an AIMI
 * key in one of those `OpenAPSAIMIPlugin.kt` sub-screens, update the matching section in this file
 * in the same change — nothing else will catch the drift.
 *
 * `AimiSettingsManifestTest` checks three things against this file: every key is in a section of
 * a routed content, a key's level is the lowest level that offers it, and a `FIELDS` section holds
 * only keys of its own level.
 */
internal object AimiSettingsScreens {

    /**
     * The PK/PD levels the guided screen offers, in tab order.
     *
     * `AimiPkpdSettingsScreen` builds its tabs from this list and routes each one to the content
     * below, so dropping a level here makes the reachability test fail instead of hiding keys.
     */
    val pkpdLevels: List<PkpdSettingsLevel> = listOf(
        PkpdSettingsLevel.SIMPLE,
        PkpdSettingsLevel.ADVANCED,
        PkpdSettingsLevel.EXPERT,
    )

    /** Which content draws each PK/PD level. */
    val pkpdContentByLevel: Map<PkpdSettingsLevel, AimiSettingsContentId> = mapOf(
        PkpdSettingsLevel.SIMPLE to AimiSettingsContentId.PkpdSimple,
        PkpdSettingsLevel.ADVANCED to AimiSettingsContentId.PkpdAdvanced,
        PkpdSettingsLevel.EXPERT to AimiSettingsContentId.PkpdExpert,
    )

    /** Contents the preference tree and the AIMI activities always reach. */
    private val alwaysRoutedContents: Set<AimiSettingsContentId> = setOf(
        AimiSettingsContentId.ControlCenter,
        AimiSettingsContentId.PreferencesRoot,
        AimiSettingsContentId.PreferencesAiKeys,
        AimiSettingsContentId.PreferencesTpo,
        AimiSettingsContentId.PreferencesPhysio,
        AimiSettingsContentId.PreferencesPatientContext,
        AimiSettingsContentId.PreferencesWomenCycle,
        AimiSettingsContentId.PreferencesInflammatory,
        AimiSettingsContentId.PreferencesThyroid,
        AimiSettingsContentId.PreferencesNightGrowth,
        AimiSettingsContentId.PreferencesLab,
        AimiSettingsContentId.PreferencesAdaptiveBasal,
        AimiSettingsContentId.PreferencesT3c,
        AimiSettingsContentId.PreferencesTrajectory,
        AimiSettingsContentId.PreferencesManualModes,
        AimiSettingsContentId.PreferencesAutodrive,
        AimiSettingsContentId.PreferencesAutodrivePrebolusVars,
        AimiSettingsContentId.PreferencesAiAuditor,
        AimiSettingsContentId.ContextEditor,
        AimiSettingsContentId.PreferencesEmergencySos,
    )

    /** Every content the navigation really reaches. */
    val routedContents: Set<AimiSettingsContentId> = buildSet {
        addAll(alwaysRoutedContents)
        pkpdLevels.forEach { level -> pkpdContentByLevel[level]?.let { add(it) } }
    }

    val sections: List<AimiSettingsSection> = listOf(
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdSimpleEnable,
            content = AimiSettingsContentId.PkpdSimple,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIPkpdEnabled,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdSimpleInsulinPreset,
            content = AimiSettingsContentId.PkpdSimple,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                DoubleKey.OApsAIMIPkpdInitialDiaH,
                DoubleKey.OApsAIMIPkpdInitialPeakMin,
                DoubleKey.OApsAIMIPkpdBoundsDiaMinH,
                DoubleKey.OApsAIMIPkpdBoundsDiaMaxH,
                DoubleKey.OApsAIMIPkpdBoundsPeakMinMin,
                DoubleKey.OApsAIMIPkpdBoundsPeakMinMax,
                DoubleKey.OApsAIMIPkpdAnchorDiaH,
                DoubleKey.OApsAIMIPkpdAnchorPeakMin,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdSimpleCorrectionPrudence,
            content = AimiSettingsContentId.PkpdSimple,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                DoubleKey.OApsAIMIIsfFusionMinFactor,
                DoubleKey.OApsAIMIIsfFusionMaxFactor,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdSimpleTailPrudence,
            content = AimiSettingsContentId.PkpdSimple,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                DoubleKey.OApsAIMISmbTailDamping,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdAdvancedLearningPace,
            content = AimiSettingsContentId.PkpdAdvanced,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH,
                DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdAdvancedStartingKinetics,
            content = AimiSettingsContentId.PkpdAdvanced,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIPkpdInitialDiaH,
                DoubleKey.OApsAIMIPkpdInitialPeakMin,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdAdvancedCustomBounds,
            content = AimiSettingsContentId.PkpdAdvanced,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIPkpdBoundsDiaMinH,
                DoubleKey.OApsAIMIPkpdBoundsDiaMaxH,
                DoubleKey.OApsAIMIPkpdBoundsPeakMinMin,
                DoubleKey.OApsAIMIPkpdBoundsPeakMinMax,
                DoubleKey.OApsAIMIPkpdAnchorDiaH,
                DoubleKey.OApsAIMIPkpdAnchorPeakMin,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdAdvancedStackAwareGuardB,
            content = AimiSettingsContentId.PkpdAdvanced,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIPkpdStackAwareGuardB,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertPeakGovernor,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIPeakGovernorEnabled,
                DoubleKey.OApsAIMIPeakGovernorLearnedWeight,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertIsfFusionBounds,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIIsfFusionMinFactor,
                DoubleKey.OApsAIMIIsfFusionMaxFactor,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertIsfFusionSlope,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIIsfFusionMaxChangePerTick,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertDynIsfTuning,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertDynIsfShadow,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIDynIsfTrajectoryShadowOnly,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertDynIsfFraction,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertSmbTailThreshold,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMISmbTailThreshold,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertSmbDampings,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMISmbTailDamping,
                DoubleKey.OApsAIMISmbExerciseDamping,
                DoubleKey.OApsAIMISmbLateFatDamping,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertReliefEnable,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertReliefFactors,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor,
                DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertIobSurveillance,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIIobSurveillanceGuard,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertPriorityMaxIob,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIPriorityMaxIobFactor,
                DoubleKey.OApsAIMIPriorityMaxIobExtraU,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ControlCenterProtectionLevel,
            content = AimiSettingsContentId.ControlCenter,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                DoubleKey.OApsAIMIMaxSMB,
                DoubleKey.OApsAIMIHighBGMaxSMB,
                DoubleKey.OApsAIMIPriorityMaxIobFactor,
                DoubleKey.OApsAIMIPriorityMaxIobExtraU,
                DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor,
                DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ControlCenterMealCaptureLevel,
            content = AimiSettingsContentId.ControlCenter,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                BooleanKey.OApsAIMIHyperTrajectoryRelease,
                BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive,
                DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep,
                DoubleKey.OApsAIMIautodrivePrebolus,
                DoubleKey.OApsAIMIautodrivesmallPrebolus,
                DoubleKey.OApsAIMIHyperEstablishedDevMgdl,
                DoubleKey.OApsAIMIHyperDeepDevMgdl,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ControlCenterStabilityLevel,
            content = AimiSettingsContentId.ControlCenter,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                DoubleKey.OApsAIMISmbTailDamping,
                DoubleKey.OApsAIMISmbExerciseDamping,
                DoubleKey.OApsAIMISmbLateFatDamping,
                BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
                BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled,
                DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ControlCenterAutonomyMode,
            content = AimiSettingsContentId.ControlCenter,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                BooleanKey.OApsAIMIautoDriveActive,
                BooleanKey.OApsAIMIHyperTrajectoryRelease,
                BooleanKey.OApsAIMIRecursiveBeliefAuthority,
                BooleanKey.OApsAIMIautoDriveAuthoritative,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PreferencesRootRemotePin,
            content = AimiSettingsContentId.PreferencesRoot,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                AimiStringKey.RemoteControlPin,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the top of `aimi_compose_ai_keys` in `OpenAPSAIMIPlugin.kt`.
            id = AimiSettingsSectionId.AiAdvisorKeys,
            content = AimiSettingsContentId.PreferencesAiKeys,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                StringKey.AimiAdvisorProvider,
                StringKey.AimiAdvisorOpenAIKey,
                StringKey.AimiAdvisorGeminiKey,
                StringKey.AimiAdvisorDeepSeekKey,
                StringKey.AimiAdvisorClaudeKey,
                AimiStringKey.AimiAdvisorClaudeModel,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PreferencesAiAdvisorLearning,
            content = AimiSettingsContentId.PreferencesAiKeys,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIAdvisorPersonalOrefMl,
                BooleanKey.OApsAIMIAdvisorLlmRichOref,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.TpoEnable,
            content = AimiSettingsContentId.PreferencesTpo,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMITpoEnabled,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.TpoOptions,
            content = AimiSettingsContentId.PreferencesTpo,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMITpoLlmConfirmEnabled,
                BooleanKey.OApsAIMITpoNotifyOnApply,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the top of `aimi_compose_physio` in `OpenAPSAIMIPlugin.kt`.
            id = AimiSettingsSectionId.PhysioModuleToggles,
            content = AimiSettingsContentId.PreferencesPhysio,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.AimiPhysioAssistantEnable,
                BooleanKey.AimiPhysioSleepDataEnable,
                BooleanKey.AimiPhysioHRVDataEnable,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PhysioSources,
            content = AimiSettingsContentId.PreferencesPhysio,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                AimiStringKey.ActivitySourceMode,
                AimiStringKey.OuraPersonalAccessToken,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the bottom of `aimi_compose_physio` in `OpenAPSAIMIPlugin.kt`.
            id = AimiSettingsSectionId.PhysioAiAnalysis,
            content = AimiSettingsContentId.PreferencesPhysio,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.AimiPhysioLLMAnalysisEnable,
                // AimiPhysioLLMProvider is NOT here: no `add(...)` in `aimi_compose_physio` shows
                // it today. It stays in `AimiSettingsManifest.REACHABILITY_DEBT`.
                BooleanKey.AimiPhysioDebugLogs,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PatientBody,
            content = AimiSettingsContentId.PreferencesPatientContext,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIweight,
                DoubleKey.OApsAIMICHO,
                DoubleKey.OApsAIMITDD7,
                BooleanKey.OApsAIMIpregnancy,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PatientPregnancyDueDate,
            content = AimiSettingsContentId.PreferencesPatientContext,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                AimiStringKey.PregnancyDueDateString,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PatientHoneymoon,
            content = AimiSettingsContentId.PreferencesPatientContext,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIhoneymoon,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PatientNightMode,
            content = AimiSettingsContentId.PreferencesPatientContext,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMInight,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.WomenCycleIntent,
            content = AimiSettingsContentId.PreferencesWomenCycle,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIwcycle,
                StringKey.OApsAIMIWCycleTrackingMode,
                StringKey.OApsAIMIWCycleContraceptive,
                DoubleKey.OApsAIMIwcycledateday,
                IntKey.OApsAIMIWCycleAvgLength,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.WomenCycleExpert,
            content = AimiSettingsContentId.PreferencesWomenCycle,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIWCycleShadow,
                BooleanKey.OApsAIMIWCycleRequireConfirm,
                DoubleKey.OApsAIMIWCycleClampMin,
                DoubleKey.OApsAIMIWCycleClampMax,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.InflammatoryContext,
            content = AimiSettingsContentId.PreferencesInflammatory,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                StringKey.OApsAIMIWCycleThyroid,
                StringKey.OApsAIMIWCycleVerneuil,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ThyroidModule,
            content = AimiSettingsContentId.PreferencesThyroid,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIThyroidEnabled,
                StringKey.OApsAIMIThyroidMode,
                StringKey.OApsAIMIThyroidManualStatus,
                StringKey.OApsAIMIThyroidTreatmentPhase,
                StringKey.OApsAIMIThyroidGuardLevel,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ThyroidDebug,
            content = AimiSettingsContentId.PreferencesThyroid,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIThyroidLogVerbosity,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the top of `aimi_compose_endo` in `OpenAPSAIMIPlugin.kt`. A
            // clinical status, the same way honeymoon and pregnancy are: if a clinician would
            // recognise "does the person have this condition, is it flaring today", it is SIMPLE.
            // AimiEndometriosisHormonalSuppression is NOT here: see
            // `AimiSettingsManifest.DEAD_KEYS`.
            id = AimiSettingsSectionId.EndoStatus,
            content = AimiSettingsContentId.PreferencesPatientContext,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.AimiEndometriosisEnable,
                BooleanKey.AimiEndometriosisPainFlare,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the bottom of `aimi_compose_endo`: the tuning gains behind the
            // status switches above.
            id = AimiSettingsSectionId.EndoTuning,
            content = AimiSettingsContentId.PreferencesPatientContext,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                IntKey.AimiEndometriosisFlareDuration,
                DoubleKey.AimiEndometriosisBasalMult,
                DoubleKey.AimiEndometriosisSmbDampen,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.NightGrowthIntent,
            content = AimiSettingsContentId.PreferencesNightGrowth,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMINightGrowthEnabled,
                IntKey.OApsAIMINightGrowthAgeYears,
                StringKey.OApsAIMINightGrowthStart,
                StringKey.OApsAIMINightGrowthEnd,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.NightGrowthBudget,
            content = AimiSettingsContentId.PreferencesNightGrowth,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMINightGrowthMaxIobExtra,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.LabMlTraining,
            content = AimiSettingsContentId.PreferencesLab,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIMLtraining,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.LabDoseLimits,
            content = AimiSettingsContentId.PreferencesLab,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIMaxSMB,
                DoubleKey.OApsAIMIHighBGMaxSMB,
                // Added so a clinician-level value read by a dozen dosing paths has a screen home.
                DoubleKey.OApsAIMIHighBg,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the `OApsxdriponeminute` item in `aimiComposeLabSubScreen()`.
            id = AimiSettingsSectionId.LabXdripOneMinute,
            content = AimiSettingsContentId.PreferencesLab,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsxdriponeminute,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.LabUnifiedReactivity,
            content = AimiSettingsContentId.PreferencesLab,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIUnifiedReactivityEnabled,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AdaptiveBasalEnable,
            content = AimiSettingsContentId.PreferencesAdaptiveBasal,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AdaptiveBasalExpert,
            content = AimiSettingsContentId.PreferencesAdaptiveBasal,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIBasalSlewLimitEnabled,
                BooleanKey.OApsAIMIBasalChannelSafetyGuards,
                BooleanKey.OApsAIMIBasalTerminalInvariants,
                BooleanKey.OApsAIMITrajBridgeBasalSurvives,
                BooleanKey.OApsAIMIBasalProjectedError,
                DoubleKey.OApsAIMIAdaptiveBasalMaxScaling,
                DoubleKey.OApsAIMIGovernanceHypoRateEnter,
                DoubleKey.OApsAIMIGovernanceHypoRateExit,
                DoubleKey.OApsAIMIGovernanceHypoBgMgdl,
                DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl,
                DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate,
                DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate,
                DoubleKey.OApsAIMIGovernanceHoldAggFloorRate,
                DoubleKey.OApsAIMIGovernanceHoldAggDecayRate,
                DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere,
                DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere,
                DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere,
                DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere,
                DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples,
                DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl,
                DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp,
                DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax,
            ),
        ),
        AimiSettingsSection(
            // Added so the adaptive-basal ceiling (a clinician-level safety limit) has a screen
            // home. Appended after the existing sections so their order is unchanged.
            id = AimiSettingsSectionId.AdaptiveBasalLimits,
            content = AimiSettingsContentId.PreferencesAdaptiveBasal,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIMaxMultiplier,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.T3cMode,
            content = AimiSettingsContentId.PreferencesT3c,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIT3cBrittleMode,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.T3cAutodriveAuthority,
            content = AimiSettingsContentId.PreferencesT3c,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIT3cAutodriveBasalAuthority,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.T3cBehaviour,
            content = AimiSettingsContentId.PreferencesT3c,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIT3cHyperBasalFloor,
                BooleanKey.OApsAIMIT3cCfrdMode,
                BooleanKey.OApsAIMIT3cCfrdExacerbationMode,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.T3cCfrdLgsFloor,
            content = AimiSettingsContentId.PreferencesT3c,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.T3cCfrdCobDelay,
            content = AimiSettingsContentId.PreferencesT3c,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIT3cCfrdCobDelayMin,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.T3cAnticipation,
            content = AimiSettingsContentId.PreferencesT3c,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIT3cActivationThreshold,
                DoubleKey.OApsAIMIT3cAggressiveness,
                DoubleKey.OApsAIMIT3cAnticipationStrength,
                BooleanKey.OApsAIMIUndeclaredCobEnabled,
                DoubleKey.OApsAIMIUndeclaredCobMaxG,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.TrajectoryGuards,
            content = AimiSettingsContentId.PreferencesTrajectory,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMITrajectoryGuardEnabled,
                BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
                BooleanKey.OApsAIMITubeVetoIgnoreFloorArtefact,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the nested "aimi_compose_tube_mpc" sub-screen in
            // `aimiComposeTrajectorySubScreen()`. AimiTubeHypoFloorMgdl and AimiTubeAggressiveness
            // are also rewritten by the transient preference overlay: see
            // `tpo/TpoPreferenceKeys.kt`.
            id = AimiSettingsSectionId.TubeAdvancedTunables,
            content = AimiSettingsContentId.PreferencesTrajectory,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.AimiTubeHypoFloorMgdl,
                DoubleKey.AimiTubeHyperBandMgdl,
                DoubleKey.AimiTubeAggressiveness,
                DoubleKey.AimiTubeBasalTrimMax,
                DoubleKey.AimiTubeKappaSafetyMargin,
            ),
        ),
        // A ceiling on insulin is the person's own setting, so it is SIMPLE, and a FIELDS list may
        // hold only one level — hence its own one-key section rather than a line in the mode list.
        // It mirrors the first item of `aimiComposeManualModesSubScreen()`.
        AimiSettingsSection(
            id = AimiSettingsSectionId.ManualModesMaxBasal,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.meal_modes_MaxBasal,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ModeBreakfast,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIBFPrebolus,
                DoubleKey.OApsAIMIBFPrebolus2,
                DoubleKey.OApsAIMIBFFactor,
                IntKey.OApsAIMIBFinterval,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ModeLunch,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMILunchPrebolus,
                DoubleKey.OApsAIMILunchPrebolus2,
                DoubleKey.OApsAIMILunchFactor,
                IntKey.OApsAIMILunchinterval,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ModeDinner,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIDinnerPrebolus,
                DoubleKey.OApsAIMIDinnerPrebolus2,
                DoubleKey.OApsAIMIDinnerFactor,
                IntKey.OApsAIMIDinnerinterval,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ModeHighCarb,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIHighCarbPrebolus,
                DoubleKey.OApsAIMIHighCarbPrebolus2,
                DoubleKey.OApsAIMIHCFactor,
                IntKey.OApsAIMIHCinterval,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ModeSnack,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMISnackPrebolus,
                DoubleKey.OApsAIMISnackFactor,
                IntKey.OApsAIMISnackinterval,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ModeMeal,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIMealPrebolus,
                DoubleKey.OApsAIMIMealFactor,
                IntKey.OApsAIMImealinterval,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ModeSleep,
            content = AimiSettingsContentId.PreferencesManualModes,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.OApsAIMIsleepFactor,
                IntKey.OApsAIMISleepinterval,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveChannel,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIautoDriveActive,
                BooleanKey.OApsAIMIautoDriveAuthoritative,
                BooleanKey.OApsAIMIHyperTrajectoryRelease,
                BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveBeliefShadow,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIRecursiveBeliefShadow,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveBeliefAuthority,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIRecursiveBeliefAuthority,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveBeliefWavelet,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIRecursiveBeliefWavelet,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveMealRise,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMITreeMealRiseFrontLoad,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveSensorConfidence,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMISensorConfidenceCgmFirst,
            ),
        ),
        // Same reason as `ManualModesMaxBasal`: the autodrive ceiling is the person's, the gestures
        // around it are not. It mirrors the `autodriveMaxBasal` line of the autodrive screen.
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveMaxBasal,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                DoubleKey.autodriveMaxBasal,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodriveGestures,
            content = AimiSettingsContentId.PreferencesAutodrive,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIMealConfirmedEarlyRelease,
                DoubleKey.OApsAIMIHyperEstablishedDevMgdl,
                DoubleKey.OApsAIMIHyperDeepDevMgdl,
                DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep,
                BooleanKey.OApsAIMIautodriveAggressiveSmbFloor,
                BooleanKey.OApsAIMIStressIsfFloor,
                BooleanKey.OApsAIMIEffortActivityProtection,
                BooleanKey.OApsAIMIRiseCeilingGuard,
                BooleanKey.OApsAIMIAnticipBasalFloor,
                DoubleKey.OApsAIMIAnticipBudgetU,
                BooleanKey.OApsAIMIAnticipMealEvidence,
                DoubleKey.OApsAIMIautodrivesmallPrebolus,
                DoubleKey.OApsAIMIautodrivePrebolus,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AutodrivePrebolusVariables,
            content = AimiSettingsContentId.PreferencesAutodrivePrebolusVars,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                IntKey.OApsAIMIAutodriveBG,
                DoubleKey.OApsAIMIcombinedDelta,
                DoubleKey.OApsAIMIAutodriveDeviation,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.AuditorProfileFactors,
            content = AimiSettingsContentId.PreferencesAiAuditor,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIAuditorProfileFactors,
            ),
        ),
        AimiSettingsSection(
            // Declared mirror of the rest of `aimi_compose_ai_auditor` in `OpenAPSAIMIPlugin.kt`.
            id = AimiSettingsSectionId.AuditorSettings,
            content = AimiSettingsContentId.PreferencesAiAuditor,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.AimiAuditorEnabled,
                StringKey.AimiAuditorMode,
                IntKey.AimiAuditorMaxPerHour,
                IntKey.AimiAuditorTimeoutSeconds,
                IntKey.AimiAuditorMinConfidence,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ContextModuleEnable,
            content = AimiSettingsContentId.ContextEditor,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIContextEnabled,
                BooleanKey.OApsAIMIContextLLMEnabled,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.ContextIntentStore,
            content = AimiSettingsContentId.ContextEditor,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.CONTROL,
            keys = listOf(
                StringKey.OApsAIMIContextStorage,
            ),
        ),
        // `OApsAIMIPkpdSetupWizardCompleted` has no section here: it is BOOKKEEPING, set by the
        // wizard UI itself when it finishes or is skipped, and the person never sees or sets it.
        // A BOOKKEEPING-only key is exempt from reachability; see `AimiSettingsManifestTest`'s
        // `userOwned` filter.
        AimiSettingsSection(
            // Declared mirror of the top of `aimi_compose_sos` in `OpenAPSAIMIPlugin.kt`.
            id = AimiSettingsSectionId.SosEnable,
            content = AimiSettingsContentId.PreferencesEmergencySos,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.AimiEmergencySosEnable,
            ),
        ),
        AimiSettingsSection(
            // The three mg/dL and minute thresholds the person sets for their own hypo safety net.
            id = AimiSettingsSectionId.SosThresholds,
            content = AimiSettingsContentId.PreferencesEmergencySos,
            level = AimiSettingsLevel.SIMPLE,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                IntKey.AimiEmergencySosThreshold,
                IntKey.AimiEmergencySosImmediateThreshold,
                IntKey.AimiEmergencySosStaleThreshold,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.SosContacts,
            content = AimiSettingsContentId.PreferencesEmergencySos,
            level = AimiSettingsLevel.ADVANCED,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                StringKey.AimiEmergencySosPhone,
                StringKey.AimiEmergencySosPhone2,
            ),
        ),
    )

    private val sectionById: Map<AimiSettingsSectionId, AimiSettingsSection> =
        sections.associateBy { it.id }

    fun section(id: AimiSettingsSectionId): AimiSettingsSection = sectionById.getValue(id)

    fun sectionsOf(content: AimiSettingsContentId): List<AimiSettingsSection> =
        sections.filter { it.content == content }

    /** The keys of these sections, in declaration order. */
    fun keysOf(vararg ids: AimiSettingsSectionId): List<NonPreferenceKey> =
        ids.flatMap { section(it).keys }

    /**
     * The keys of these sections as preference items, in declaration order.
     *
     * A preference screen can only show a [PreferenceKey], so a runtime state key would be
     * dropped here. `AimiSettingsManifestTest` forbids one in a `FIELDS` section, which is why
     * this cast is safe.
     */
    fun preferenceKeysOf(vararg ids: AimiSettingsSectionId): List<PreferenceKey> =
        ids.flatMap { section(it).keys }.filterIsInstance<PreferenceKey>()
}
