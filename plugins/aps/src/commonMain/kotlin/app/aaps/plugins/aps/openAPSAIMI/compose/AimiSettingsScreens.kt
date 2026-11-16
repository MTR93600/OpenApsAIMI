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
    PreferencesRoot,

    /** The Essentials page, opened by the first row of the AIMI settings. See [AimiEssentials]. */
    PreferencesEssentials,
    PreferencesAiKeys,
    PreferencesTpo,
    PreferencesPatientContext,
    PreferencesDoseLimits,

    /** The feature cards (step S2). Each card is one page; see [AimiFeatureCard]. */
    CardMeals,
    CardActivity,
    CardNight,
    CardWomenCycle,
    CardIllness,
    CardPkpd,
    CardPhysio,
    CardAutodrive,
    CardSafetyAlarms,

    /** Pages inside a card. */
    PreferencesManualModes,
    PreferencesNightGrowth,
    PreferencesThyroid,
    PreferencesEndometriosis,
    PreferencesT3c,
    PreferencesAutodrivePrebolusVars,

    /** The Expert area: the family pages generated from the manifest, and the technical pages. */
    ExpertFamilies,
    PreferencesAdaptiveBasal,
    PreferencesTrajectory,
    PreferencesAiAuditor,
    ContextEditor,

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
    PkpdSimpleCorrectionPrudence,
    PkpdSimpleTailPrudence,
    PkpdAdvancedLearningPace,
    PkpdAdvancedCurveLearningGate,
    PkpdExpertPeakGovernor,
    PkpdExpertDiaGovernor,
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
    PreferencesRootRemotePin,
    EssentialsPatient,
    EssentialsDoseLimits,
    PreferencesAiAdvisorLearning,
    AiAdvisorKeys,
    TpoEnable,
    TpoOptions,
    PatientBody,
    PatientPregnancyDueDate,
    PatientHoneymoon,
    DoseLimits,
    ManualModesMaxBasal,
    MealsAnticipation,
    ModeBreakfast,
    ModeLunch,
    ModeDinner,
    ModeHighCarb,
    ModeSnack,
    ModeMeal,
    ModeSleep,
    ActivityProtection,
    ActivityBasalCap,
    NightMode,
    NightGrowthAge,
    NightGrowthIntent,
    NightGrowthBudget,
    WomenCycleIntent,
    WomenCycleExpert,
    InflammatoryContext,
    ThyroidModule,
    ThyroidDebug,
    EndoStatus,
    EndoTuning,
    T3cMode,
    T3cAutodriveAuthority,
    T3cBehaviour,
    T3cCfrdLgsFloor,
    T3cCfrdCobDelay,
    T3cAnticipation,
    CardPkpdEnable,
    PhysioModuleToggles,
    PhysioDataUse,
    PhysioSources,
    PhysioAiAnalysis,
    PhysioStressFloor,
    PhysioSensorConfidence,
    AutodriveChannel,
    AutodriveBeliefShadow,
    AutodriveBeliefAuthority,
    AutodriveBeliefWavelet,
    AutodriveMealRise,
    AutodriveMaxBasal,
    AutodriveGestures,
    AutodrivePrebolusVariables,
    SosEnable,
    SosThresholds,
    SosContacts,
    AdaptiveBasalEnable,
    AdaptiveBasalExpert,
    AdaptiveBasalLimits,
    TrajectoryGuards,
    TrajectoryPredictionGuards,
    TubeAdvancedTunables,
    AuditorProfileFactors,
    AuditorSettings,
    ContextModuleEnable,
    ExpertProtection,
    ExpertMealCapture,
    ExpertStability,
    ExpertPhysio,
    ExpertAutonomy,
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
 * PK/PD learning pace chips are: a control that writes expert learning keys.
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
 * Since step S2 every page of [AimiPreferenceTree] reads its AIMI keys from here, through
 * [AimiSettingsScreens.preferenceKeysOf], so a section can never drift from what is drawn: it IS
 * what is drawn. The tree only adds the list labels of a choice key and the rows that are not AIMI
 * keys (platform screens, info rows). The three PK/PD contents draw their sections the same way.
 * The Expert family pages are generated from the manifest (see `expertFamilySections`).
 *
 * `AimiSettingsManifestTest` checks three things against this file: every key is in a section of
 * a routed content, a key's level is the lowest level that offers it, and a `FIELDS` section holds
 * only keys of its own level. `AimiPreferenceTreeTest` checks the other half: every key of a
 * section drawn by the preference tree can really be reached there by browsing.
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
        AimiSettingsContentId.PreferencesRoot,
        AimiSettingsContentId.PreferencesEssentials,
        AimiSettingsContentId.PreferencesAiKeys,
        AimiSettingsContentId.PreferencesTpo,
        AimiSettingsContentId.PreferencesPatientContext,
        AimiSettingsContentId.PreferencesDoseLimits,
        AimiSettingsContentId.CardMeals,
        AimiSettingsContentId.CardActivity,
        AimiSettingsContentId.CardNight,
        AimiSettingsContentId.CardWomenCycle,
        AimiSettingsContentId.CardIllness,
        AimiSettingsContentId.CardPkpd,
        AimiSettingsContentId.CardPhysio,
        AimiSettingsContentId.CardAutodrive,
        AimiSettingsContentId.CardSafetyAlarms,
        AimiSettingsContentId.PreferencesManualModes,
        AimiSettingsContentId.PreferencesNightGrowth,
        AimiSettingsContentId.PreferencesThyroid,
        AimiSettingsContentId.PreferencesEndometriosis,
        AimiSettingsContentId.PreferencesT3c,
        AimiSettingsContentId.PreferencesAutodrivePrebolusVars,
        AimiSettingsContentId.ExpertFamilies,
        AimiSettingsContentId.PreferencesAdaptiveBasal,
        AimiSettingsContentId.PreferencesTrajectory,
        AimiSettingsContentId.PreferencesAiAuditor,
        AimiSettingsContentId.ContextEditor,
    )

    /** Every content the navigation really reaches. */
    val routedContents: Set<AimiSettingsContentId> = buildSet {
        addAll(alwaysRoutedContents)
        pkpdLevels.forEach { level -> pkpdContentByLevel[level]?.let { add(it) } }
    }

    /** Sections written by hand: each one is what a page draws, or mirrors it. */
    private val handSections: List<AimiSettingsSection> = listOf(
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
            id = AimiSettingsSectionId.PkpdAdvancedCurveLearningGate,
            content = AimiSettingsContentId.PkpdAdvanced,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIPkpdCurveLearningGate,
            ),
        ),
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertPeakGovernor,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIPeakGovernorEnabled,
            ),
        ),
        // The field off switch of the causal DIA context (P1b): it must be reachable from the app.
        AimiSettingsSection(
            id = AimiSettingsSectionId.PkpdExpertDiaGovernor,
            content = AimiSettingsContentId.PkpdExpert,
            level = AimiSettingsLevel.EXPERT,
            kind = AimiSettingsSectionKind.FIELDS,
            keys = listOf(
                BooleanKey.OApsAIMIDiaGovernorEnabled,
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
        fields(AimiSettingsSectionId.PreferencesRootRemotePin, AimiSettingsContentId.PreferencesRoot, AimiSettingsLevel.ADVANCED, AimiStringKey.RemoteControlPin),
        // The AIMI keys of the Essentials page, in page order. `AimiEssentialsTest` checks that they
        // are exactly the AIMI keys of `AimiEssentials.rows`; the OpenAPS keys of the page (max basal,
        // max IOB, LGS threshold, SMB switch) are not AIMI keys and have no manifest entry.
        // Age and due date are SIMPLE since step S3 (owner decision): they are the person's own data.
        fields(
            AimiSettingsSectionId.EssentialsPatient, AimiSettingsContentId.PreferencesEssentials, AimiSettingsLevel.SIMPLE,
            DoubleKey.OApsAIMIweight, DoubleKey.OApsAIMITDD7, DoubleKey.OApsAIMICHO, IntKey.OApsAIMINightGrowthAgeYears,
            BooleanKey.OApsAIMIhoneymoon, BooleanKey.OApsAIMIpregnancy, AimiStringKey.PregnancyDueDateString,
        ),
        fields(
            AimiSettingsSectionId.EssentialsDoseLimits, AimiSettingsContentId.PreferencesEssentials, AimiSettingsLevel.SIMPLE,
            DoubleKey.meal_modes_MaxBasal, DoubleKey.autodriveMaxBasal, DoubleKey.OApsAIMIMaxSMB, DoubleKey.OApsAIMIHighBGMaxSMB, DoubleKey.OApsAIMIMaxMultiplier,
        ),
        fields(
            AimiSettingsSectionId.AiAdvisorKeys, AimiSettingsContentId.PreferencesAiKeys, AimiSettingsLevel.EXPERT,
            StringKey.AimiAdvisorProvider, StringKey.AimiAdvisorOpenAIKey, StringKey.AimiAdvisorGeminiKey, StringKey.AimiAdvisorDeepSeekKey,
            StringKey.AimiAdvisorClaudeKey, AimiStringKey.AimiAdvisorClaudeModel,
        ),
        fields(
            AimiSettingsSectionId.PreferencesAiAdvisorLearning, AimiSettingsContentId.PreferencesAiKeys, AimiSettingsLevel.EXPERT,
            BooleanKey.OApsAIMIAdvisorPersonalOrefMl, BooleanKey.OApsAIMIAdvisorLlmRichOref,
        ),
        fields(AimiSettingsSectionId.TpoEnable, AimiSettingsContentId.PreferencesTpo, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMITpoEnabled),
        fields(
            AimiSettingsSectionId.TpoOptions, AimiSettingsContentId.PreferencesTpo, AimiSettingsLevel.EXPERT,
            BooleanKey.OApsAIMITpoLlmConfirmEnabled, BooleanKey.OApsAIMITpoNotifyOnApply,
        ),
        // "About you": the same person data as the Essentials page, on a page that search can open.
        fields(
            AimiSettingsSectionId.PatientBody, AimiSettingsContentId.PreferencesPatientContext, AimiSettingsLevel.SIMPLE,
            DoubleKey.OApsAIMIweight, DoubleKey.OApsAIMICHO, DoubleKey.OApsAIMITDD7, BooleanKey.OApsAIMIpregnancy,
        ),
        fields(AimiSettingsSectionId.PatientPregnancyDueDate, AimiSettingsContentId.PreferencesPatientContext, AimiSettingsLevel.SIMPLE, AimiStringKey.PregnancyDueDateString),
        fields(AimiSettingsSectionId.PatientHoneymoon, AimiSettingsContentId.PreferencesPatientContext, AimiSettingsLevel.SIMPLE, BooleanKey.OApsAIMIhoneymoon),
        // The SMB limits, on a page that search can open (the Essentials page shows them too).
        fields(
            AimiSettingsSectionId.DoseLimits, AimiSettingsContentId.PreferencesDoseLimits, AimiSettingsLevel.SIMPLE,
            DoubleKey.OApsAIMIMaxSMB, DoubleKey.OApsAIMIHighBGMaxSMB, DoubleKey.OApsAIMIHighBg,
        ),

        // ---- Feature card: meals ----
        fields(AimiSettingsSectionId.ManualModesMaxBasal, AimiSettingsContentId.CardMeals, AimiSettingsLevel.SIMPLE, DoubleKey.meal_modes_MaxBasal),
        fields(
            AimiSettingsSectionId.MealsAnticipation, AimiSettingsContentId.CardMeals, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMIAnticipBasalFloor, DoubleKey.OApsAIMIAnticipBudgetU, BooleanKey.OApsAIMIAnticipMealEvidence,
        ),
        fields(
            AimiSettingsSectionId.ModeBreakfast, AimiSettingsContentId.PreferencesManualModes, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMIBFPrebolus, DoubleKey.OApsAIMIBFPrebolus2, DoubleKey.OApsAIMIBFFactor, IntKey.OApsAIMIBFinterval,
        ),
        fields(
            AimiSettingsSectionId.ModeLunch, AimiSettingsContentId.PreferencesManualModes, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMILunchPrebolus, DoubleKey.OApsAIMILunchPrebolus2, DoubleKey.OApsAIMILunchFactor, IntKey.OApsAIMILunchinterval,
        ),
        fields(
            AimiSettingsSectionId.ModeDinner, AimiSettingsContentId.PreferencesManualModes, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMIDinnerPrebolus, DoubleKey.OApsAIMIDinnerPrebolus2, DoubleKey.OApsAIMIDinnerFactor, IntKey.OApsAIMIDinnerinterval,
        ),
        fields(
            AimiSettingsSectionId.ModeHighCarb, AimiSettingsContentId.PreferencesManualModes, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMIHighCarbPrebolus, DoubleKey.OApsAIMIHighCarbPrebolus2, DoubleKey.OApsAIMIHCFactor, IntKey.OApsAIMIHCinterval,
        ),
        fields(
            AimiSettingsSectionId.ModeSnack, AimiSettingsContentId.PreferencesManualModes, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMISnackPrebolus, DoubleKey.OApsAIMISnackFactor, IntKey.OApsAIMISnackinterval,
        ),
        fields(
            AimiSettingsSectionId.ModeMeal, AimiSettingsContentId.PreferencesManualModes, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMIMealPrebolus, DoubleKey.OApsAIMIMealFactor, IntKey.OApsAIMImealinterval,
        ),
        fields(
            AimiSettingsSectionId.ModeSleep, AimiSettingsContentId.PreferencesManualModes, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMIsleepFactor, IntKey.OApsAIMISleepinterval,
        ),

        // ---- Feature card: exercise and activity ----
        fields(
            AimiSettingsSectionId.ActivityProtection, AimiSettingsContentId.CardActivity, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMIEffortActivityProtection, DoubleKey.OApsAIMISmbExerciseDamping,
        ),
        // Read by the basal cap of the correction path during exercise; it had no screen before step
        // S2. It keeps its manifest level (ADVANCED); the key map's EXPERT target is for the level step.
        fields(AimiSettingsSectionId.ActivityBasalCap, AimiSettingsContentId.CardActivity, AimiSettingsLevel.ADVANCED, DoubleKey.OApsAIMIActivityBasalCapFactor),

        // ---- Feature card: night ----
        fields(AimiSettingsSectionId.NightMode, AimiSettingsContentId.CardNight, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMInight),
        // The age decides whether night growth support is on when its own switch was never set.
        fields(AimiSettingsSectionId.NightGrowthAge, AimiSettingsContentId.PreferencesNightGrowth, AimiSettingsLevel.SIMPLE, IntKey.OApsAIMINightGrowthAgeYears),
        fields(
            AimiSettingsSectionId.NightGrowthIntent, AimiSettingsContentId.PreferencesNightGrowth, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMINightGrowthEnabled, StringKey.OApsAIMINightGrowthStart, StringKey.OApsAIMINightGrowthEnd,
        ),
        fields(AimiSettingsSectionId.NightGrowthBudget, AimiSettingsContentId.PreferencesNightGrowth, AimiSettingsLevel.EXPERT, DoubleKey.OApsAIMINightGrowthMaxIobExtra),

        // ---- Feature card: women's cycle ----
        fields(
            AimiSettingsSectionId.WomenCycleIntent, AimiSettingsContentId.CardWomenCycle, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMIwcycle, StringKey.OApsAIMIWCycleTrackingMode, StringKey.OApsAIMIWCycleContraceptive,
            DoubleKey.OApsAIMIwcycledateday, IntKey.OApsAIMIWCycleAvgLength,
        ),
        fields(
            AimiSettingsSectionId.WomenCycleExpert, AimiSettingsContentId.CardWomenCycle, AimiSettingsLevel.EXPERT,
            BooleanKey.OApsAIMIWCycleShadow, BooleanKey.OApsAIMIWCycleRequireConfirm, DoubleKey.OApsAIMIWCycleClampMin, DoubleKey.OApsAIMIWCycleClampMax,
        ),

        // ---- Feature card: illness and inflammation ----
        fields(
            AimiSettingsSectionId.InflammatoryContext, AimiSettingsContentId.CardIllness, AimiSettingsLevel.ADVANCED,
            StringKey.OApsAIMIWCycleThyroid, StringKey.OApsAIMIWCycleVerneuil,
        ),
        fields(
            AimiSettingsSectionId.ThyroidModule, AimiSettingsContentId.PreferencesThyroid, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMIThyroidEnabled, StringKey.OApsAIMIThyroidMode, StringKey.OApsAIMIThyroidManualStatus,
            StringKey.OApsAIMIThyroidTreatmentPhase, StringKey.OApsAIMIThyroidGuardLevel,
        ),
        fields(AimiSettingsSectionId.ThyroidDebug, AimiSettingsContentId.PreferencesThyroid, AimiSettingsLevel.EXPERT, BooleanKey.OApsAIMIThyroidLogVerbosity),
        fields(
            AimiSettingsSectionId.EndoStatus, AimiSettingsContentId.PreferencesEndometriosis, AimiSettingsLevel.SIMPLE,
            BooleanKey.AimiEndometriosisEnable, BooleanKey.AimiEndometriosisPainFlare,
        ),
        fields(
            AimiSettingsSectionId.EndoTuning, AimiSettingsContentId.PreferencesEndometriosis, AimiSettingsLevel.EXPERT,
            DoubleKey.AimiEndometriosisBasalMult, DoubleKey.AimiEndometriosisSmbDampen, IntKey.AimiEndometriosisFlareDuration,
        ),
        fields(AimiSettingsSectionId.T3cMode, AimiSettingsContentId.PreferencesT3c, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMIT3cBrittleMode),
        fields(AimiSettingsSectionId.T3cAutodriveAuthority, AimiSettingsContentId.PreferencesT3c, AimiSettingsLevel.EXPERT, BooleanKey.OApsAIMIT3cAutodriveBasalAuthority),
        fields(
            AimiSettingsSectionId.T3cBehaviour, AimiSettingsContentId.PreferencesT3c, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMIT3cHyperBasalFloor, BooleanKey.OApsAIMIT3cCfrdMode, BooleanKey.OApsAIMIT3cCfrdExacerbationMode,
        ),
        fields(AimiSettingsSectionId.T3cCfrdLgsFloor, AimiSettingsContentId.PreferencesT3c, AimiSettingsLevel.SIMPLE, DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl),
        fields(AimiSettingsSectionId.T3cCfrdCobDelay, AimiSettingsContentId.PreferencesT3c, AimiSettingsLevel.EXPERT, DoubleKey.OApsAIMIT3cCfrdCobDelayMin),
        fields(
            AimiSettingsSectionId.T3cAnticipation, AimiSettingsContentId.PreferencesT3c, AimiSettingsLevel.ADVANCED,
            DoubleKey.OApsAIMIT3cActivationThreshold, DoubleKey.OApsAIMIT3cAggressiveness, DoubleKey.OApsAIMIT3cAnticipationStrength,
            BooleanKey.OApsAIMIUndeclaredCobEnabled, DoubleKey.OApsAIMIUndeclaredCobMaxG,
        ),

        // ---- Feature card: PK/PD learning (the guided screen draws the rest, see the PK/PD contents) ----
        fields(AimiSettingsSectionId.CardPkpdEnable, AimiSettingsContentId.CardPkpd, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMIPkpdEnabled),

        // ---- Feature card: physio (sources, assistant, tree) ----
        fields(AimiSettingsSectionId.PhysioModuleToggles, AimiSettingsContentId.CardPhysio, AimiSettingsLevel.ADVANCED, BooleanKey.AimiPhysioAssistantEnable),
        // Step S3b: they act with the assistant off too (the tree and the snapshot read the readings).
        fields(
            AimiSettingsSectionId.PhysioDataUse, AimiSettingsContentId.CardPhysio, AimiSettingsLevel.ADVANCED,
            BooleanKey.AimiPhysioSleepDataEnable, BooleanKey.AimiPhysioHRVDataEnable,
        ),
        fields(
            AimiSettingsSectionId.PhysioSources, AimiSettingsContentId.CardPhysio, AimiSettingsLevel.EXPERT,
            AimiStringKey.ActivitySourceMode, AimiStringKey.OuraPersonalAccessToken,
        ),
        fields(
            AimiSettingsSectionId.PhysioAiAnalysis, AimiSettingsContentId.CardPhysio, AimiSettingsLevel.EXPERT,
            BooleanKey.AimiPhysioLLMAnalysisEnable, BooleanKey.AimiHrvBaselinePerNight,
        ),
        fields(AimiSettingsSectionId.PhysioStressFloor, AimiSettingsContentId.CardPhysio, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMIStressIsfFloor),
        fields(AimiSettingsSectionId.PhysioSensorConfidence, AimiSettingsContentId.CardPhysio, AimiSettingsLevel.EXPERT, BooleanKey.OApsAIMISensorConfidenceCgmFirst),

        // ---- Feature card: Autodrive ----
        fields(
            AimiSettingsSectionId.AutodriveChannel, AimiSettingsContentId.CardAutodrive, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMIautoDriveActive, BooleanKey.OApsAIMIautoDriveAuthoritative,
            BooleanKey.OApsAIMIHyperTrajectoryRelease, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive,
        ),
        fields(AimiSettingsSectionId.AutodriveBeliefShadow, AimiSettingsContentId.CardAutodrive, AimiSettingsLevel.EXPERT, BooleanKey.OApsAIMIRecursiveBeliefShadow),
        fields(AimiSettingsSectionId.AutodriveBeliefAuthority, AimiSettingsContentId.CardAutodrive, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMIRecursiveBeliefAuthority),
        fields(AimiSettingsSectionId.AutodriveBeliefWavelet, AimiSettingsContentId.CardAutodrive, AimiSettingsLevel.EXPERT, BooleanKey.OApsAIMIRecursiveBeliefWavelet),
        fields(AimiSettingsSectionId.AutodriveMealRise, AimiSettingsContentId.CardAutodrive, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMITreeMealRiseFrontLoad),
        fields(AimiSettingsSectionId.AutodriveMaxBasal, AimiSettingsContentId.CardAutodrive, AimiSettingsLevel.SIMPLE, DoubleKey.autodriveMaxBasal),
        fields(
            AimiSettingsSectionId.AutodriveGestures, AimiSettingsContentId.CardAutodrive, AimiSettingsLevel.ADVANCED,
            BooleanKey.OApsAIMIMealConfirmedEarlyRelease, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, DoubleKey.OApsAIMIHyperDeepDevMgdl,
            DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, BooleanKey.OApsAIMIautodriveAggressiveSmbFloor, BooleanKey.OApsAIMIRiseCeilingGuard,
            DoubleKey.OApsAIMIautodrivesmallPrebolus, DoubleKey.OApsAIMIautodrivePrebolus,
        ),
        // The two weights of the glucose delta. The Autodrive BG and deviation keys are not here:
        // nothing read them, step S3 deleted them.
        fields(
            AimiSettingsSectionId.AutodrivePrebolusVariables, AimiSettingsContentId.PreferencesAutodrivePrebolusVars, AimiSettingsLevel.EXPERT,
            DoubleKey.OApsAIMIcombinedDelta, DoubleKey.OApsAIMIAutodriveAcceleration,
        ),

        // ---- Feature card: safety alarms (SOS) ----
        fields(AimiSettingsSectionId.SosEnable, AimiSettingsContentId.CardSafetyAlarms, AimiSettingsLevel.ADVANCED, BooleanKey.AimiEmergencySosEnable),
        // The three mg/dL and minute thresholds the person sets for their own hypo safety net.
        fields(
            AimiSettingsSectionId.SosThresholds, AimiSettingsContentId.CardSafetyAlarms, AimiSettingsLevel.SIMPLE,
            IntKey.AimiEmergencySosThreshold, IntKey.AimiEmergencySosImmediateThreshold, IntKey.AimiEmergencySosStaleThreshold,
        ),
        fields(
            AimiSettingsSectionId.SosContacts, AimiSettingsContentId.CardSafetyAlarms, AimiSettingsLevel.ADVANCED,
            StringKey.AimiEmergencySosPhone, StringKey.AimiEmergencySosPhone2,
        ),

        // ---- Expert area: the technical pages ----
        fields(AimiSettingsSectionId.AdaptiveBasalEnable, AimiSettingsContentId.PreferencesAdaptiveBasal, AimiSettingsLevel.ADVANCED, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled),
        fields(
            AimiSettingsSectionId.AdaptiveBasalExpert, AimiSettingsContentId.PreferencesAdaptiveBasal, AimiSettingsLevel.EXPERT,
            BooleanKey.OApsAIMIBasalSlewLimitEnabled, BooleanKey.OApsAIMIBasalChannelSafetyGuards, BooleanKey.OApsAIMIBasalTerminalInvariants,
            BooleanKey.OApsAIMITrajBridgeBasalSurvives, BooleanKey.OApsAIMIBasalProjectedError, BooleanKey.OApsAIMIDynamicBasalStackCap,
            BooleanKey.OApsAIMIForcedTbrRealPredDelta, BooleanKey.OApsAIMIBasalStackTerminalCap, BooleanKey.OApsAIMIEngineHyperRateBound,
            DoubleKey.OApsAIMIAdaptiveBasalMaxScaling, DoubleKey.OApsAIMIGovernanceHypoRateEnter, DoubleKey.OApsAIMIGovernanceHypoRateExit,
            DoubleKey.OApsAIMIGovernanceHypoBgMgdl, DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl, DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate,
            DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate, DoubleKey.OApsAIMIGovernanceHoldAggFloorRate, DoubleKey.OApsAIMIGovernanceHoldAggDecayRate,
            DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere, DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere,
            DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere, DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere,
            DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples, DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl,
            DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp, DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax,
        ),
        fields(AimiSettingsSectionId.AdaptiveBasalLimits, AimiSettingsContentId.PreferencesAdaptiveBasal, AimiSettingsLevel.SIMPLE, DoubleKey.OApsAIMIMaxMultiplier),
        fields(
            AimiSettingsSectionId.TrajectoryGuards, AimiSettingsContentId.PreferencesTrajectory, AimiSettingsLevel.EXPERT,
            BooleanKey.OApsAIMITrajectoryGuardEnabled, BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
            BooleanKey.OApsAIMITubeVetoIgnoreFloorArtefact, BooleanKey.OApsAIMITubeHyperClampPhysicalBound,
        ),
        // Expert flags of the PK/PD prediction curves (step P3: moved off the PK/PD screen).
        fields(
            AimiSettingsSectionId.TrajectoryPredictionGuards, AimiSettingsContentId.PreferencesTrajectory, AimiSettingsLevel.EXPERT,
            BooleanKey.OApsAIMIPkpdEndogenousReversion, BooleanKey.OApsAIMIPkpdHyperReversion, BooleanKey.OApsAIMIPkpdStackAwareGuardB,
        ),
        fields(
            AimiSettingsSectionId.TubeAdvancedTunables, AimiSettingsContentId.PreferencesTrajectory, AimiSettingsLevel.EXPERT,
            DoubleKey.AimiTubeHypoFloorMgdl, DoubleKey.AimiTubeHyperBandMgdl, DoubleKey.AimiTubeAggressiveness,
            DoubleKey.AimiTubeBasalTrimMax, DoubleKey.AimiTubeKappaSafetyMargin,
        ),
        fields(AimiSettingsSectionId.AuditorProfileFactors, AimiSettingsContentId.PreferencesAiAuditor, AimiSettingsLevel.EXPERT, BooleanKey.OApsAIMIAuditorProfileFactors),
        fields(
            AimiSettingsSectionId.AuditorSettings, AimiSettingsContentId.PreferencesAiAuditor, AimiSettingsLevel.EXPERT,
            BooleanKey.AimiAuditorEnabled, StringKey.AimiAuditorMode, IntKey.AimiAuditorMaxPerHour,
            IntKey.AimiAuditorTimeoutSeconds, IntKey.AimiAuditorMinConfidence,
        ),

        // ---- The context screen (its own activity, opened from the tree) ----
        // The stored context intents (`OApsAIMIContextStorage`) are not here: the context module writes
        // them itself, so they are internal state (step S3), not a setting.
        fields(
            AimiSettingsSectionId.ContextModuleEnable, AimiSettingsContentId.ContextEditor, AimiSettingsLevel.EXPERT,
            BooleanKey.OApsAIMIContextEnabled, BooleanKey.OApsAIMIContextLLMEnabled,
        ),
    )

    /**
     * The Expert family pages, generated from the manifest: every EXPERT key the person owns that no
     * page above offers, grouped by family. A new EXPERT key lands here on the day it is written.
     * Keys in [AimiSettingsManifest.REACHABILITY_DEBT] stay out: they have no screen yet, on purpose.
     */
    private val expertFamilySections: List<AimiSettingsSection> = run {
        val onHandPages = handSections.flatMap { it.keys }.map { it.key }.toSet()
        AimiBehaviorFamilyId.entries.map { family ->
            AimiSettingsSection(
                id = expertSectionIdOf(family),
                content = AimiSettingsContentId.ExpertFamilies,
                level = AimiSettingsLevel.EXPERT,
                kind = AimiSettingsSectionKind.FIELDS,
                keys = AimiSettingsManifest.entries
                    .filter { it.family == family && it.level == AimiSettingsLevel.EXPERT && it.userOwned && it.key is PreferenceKey }
                    .map { it.key }
                    .filterNot { it.key in onHandPages || it.key in AimiSettingsManifest.REACHABILITY_DEBT },
            )
        }
    }

    val sections: List<AimiSettingsSection> = handSections + expertFamilySections

    /** The id of the generated Expert section of [family]. */
    fun expertSectionIdOf(family: AimiBehaviorFamilyId): AimiSettingsSectionId = when (family) {
        AimiBehaviorFamilyId.Protection  -> AimiSettingsSectionId.ExpertProtection
        AimiBehaviorFamilyId.MealCapture -> AimiSettingsSectionId.ExpertMealCapture
        AimiBehaviorFamilyId.Stability   -> AimiSettingsSectionId.ExpertStability
        AimiBehaviorFamilyId.Physio      -> AimiSettingsSectionId.ExpertPhysio
        AimiBehaviorFamilyId.Autonomy    -> AimiSettingsSectionId.ExpertAutonomy
    }

    /** A FIELDS section: one row per key, every key of [level]. */
    private fun fields(
        id: AimiSettingsSectionId,
        content: AimiSettingsContentId,
        level: AimiSettingsLevel,
        vararg keys: NonPreferenceKey,
    ): AimiSettingsSection = AimiSettingsSection(id, content, level, AimiSettingsSectionKind.FIELDS, keys.toList())

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
