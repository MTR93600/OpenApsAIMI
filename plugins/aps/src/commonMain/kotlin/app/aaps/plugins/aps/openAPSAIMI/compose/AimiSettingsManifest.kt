package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey

/**
 * How much a setting asks of the person who moves it.
 *
 * - [SIMPLE] the person's own therapy: profile shaped values and safety limits. If a clinician
 *   would know what it means, it belongs here.
 * - [ADVANCED] intent. One knob that shapes how a whole family behaves, the way the PK/PD
 *   sliders do. The person says "be more careful with meals", not "min factor 0.75".
 * - [EXPERT] everything else: gains, bounds, per tick budgets, shadow switches and runtime
 *   state. The screen must say plainly that nobody checks behind you.
 *
 * The constants are declared from the lightest to the heaviest, so comparing two levels and
 * taking the smallest of a list both mean what they look like.
 */
internal enum class AimiSettingsLevel { SIMPLE, ADVANCED, EXPERT }

/**
 * One way a stored value can be written.
 *
 * This is not decoration. The transient preference overlay rewrites some protection keys from
 * inside a loop tick, and the PK/PD learner saves its own state the same way. Without this fact
 * a settings audit would tell the person they changed a value they never touched.
 *
 * A key can have more than one writer, which is why [AimiSettingEntry.writers] is a set: the
 * person types the lunch factor on the mode screen and the transient overlay also rewrites it.
 * One enum value cannot hold both facts, and squeezing them into one told the audit screen the
 * wrong story in both directions.
 *
 * - [USER] a settings screen writes it, so the person owns the value.
 * - [SLIDER] a simplified slider or a family level writes it.
 * - [PRESET] a preset writes it, for example the PK/PD insulin presets.
 * - [LOOP] code writes it during a loop tick. Read such a value as loop output, not as a choice
 *   the person made.
 * - [BOOKKEEPING] code stores a flag the person never sets and never sees, like "the setup wizard
 *   has run". It needs no screen.
 */
internal enum class AimiSettingsWriter { USER, SLIDER, PRESET, LOOP, BOOKKEEPING }

/**
 * One AIMI setting, with the facts this manifest is allowed to hold.
 *
 * It holds no value: no default and no bound. Those live on the key itself, which is their only
 * legitimate source.
 *
 * [writers] says who may write the value, not who did. [userOwned] is the fact the reachability
 * rule needs: a value the person may set must be a value the person can find.
 */
internal data class AimiSettingEntry(
    val key: NonPreferenceKey,
    val level: AimiSettingsLevel,
    val family: AimiBehaviorFamilyId,
    val writers: Set<AimiSettingsWriter>,
    /**
     * Set true for a secret this manifest must declare because the key itself does not say so:
     * today only the two emergency phone numbers. Read [secret], never this.
     */
    val declaredSecret: Boolean = false,
) {

    /**
     * True when the stored value is a secret or personal data: a credential, a PIN or a phone number.
     *
     * A secret key's VALUE is never displayed, never logged and never exported — only whether it is
     * set. The audit screen checks this before it shows a stored value next to its default; for a
     * secret key it may only show "set" or "not set".
     *
     * This is DERIVED first and declared second. A hand-kept list is what let the remote-control PIN
     * and the Oura token through: both say plainly what they are on the key itself, and the manifest
     * disagreed. The key's own word wins here, so a new credential is secret the day it is written,
     * with nobody having to remember this file.
     */
    val secret: Boolean
        get() = declaredSecret || (key as? StringPreferenceKey)?.let { it.isPassword || it.isPin } == true


    /** Same entry, written as one or more writers in a row. */
    constructor(
        key: NonPreferenceKey,
        level: AimiSettingsLevel,
        family: AimiBehaviorFamilyId,
        vararg writers: AimiSettingsWriter,
    ) : this(key, level, family, writers.toSet())

    /** True when a settings screen, a slider or a preset may write the value. */
    val userOwned: Boolean
        get() = writers.any {
            it == AimiSettingsWriter.USER || it == AimiSettingsWriter.SLIDER || it == AimiSettingsWriter.PRESET
        }

    /** True when code writes the value during a loop tick, whoever else may write it too. */
    val loopWrites: Boolean
        get() = AimiSettingsWriter.LOOP in writers
}

/**
 * The one list of every AIMI setting, with its level, its family and its writer.
 *
 * It extends [AimiBehaviorFamilyRegistry] instead of replacing it: the registry says which keys
 * a family covers for the Control Center, this manifest says what each key is. Where a key is in
 * both, the family here is the family the registry already gave it.
 *
 * ## Scope
 * [scopedKeys] is every AIMI owned key: an entry of [BooleanKey], [DoubleKey], [IntKey] or
 * [StringKey] whose name starts with one of [AIMI_NAME_PREFIXES], plus the keys named one by one
 * in [EXTRA_SCOPED_KEY_NAMES], plus every entry of [AimiStringKey] and [AimiLongKey]. A new AIMI
 * key breaks `AimiSettingsManifestTest` on the day it is written.
 *
 * The scope used to be the `OApsAIMI` prefix alone, which left about 45 AIMI settings outside
 * every guarantee, among them the two AIMI max basal values and the two tube values the
 * transient overlay rewrites. A prefix is a naming habit, not a fact, so the test also walks the
 * shared enums for anything that merely looks AIMI shaped: such a key must be in scope or named
 * in [NOT_AIMI_KEY_NAMES] with a reason. The scope cannot shrink again without that test failing.
 *
 * ## The exemption lists
 * They exist so the tests can be switched on today instead of "one day". Each has frozen
 * membership: a test holds the list it shipped with, so a key can only leave.
 *
 * - [UNCLASSIFIED] keys in scope with no entry here. It is empty, and a test says it can never
 *   grow. The day it has stayed empty for good it can be deleted.
 * - [REACHABILITY_DEBT] live keys with an entry here that no settings section offers, so nobody
 *   can correct a stale value. No `SIMPLE` key may be in it: a value the person owns must be a
 *   value the person can find.
 * - [DEAD_KEYS] keys nothing reads and nothing writes. They are deletion candidates, not
 *   settings, so they need no screen.
 */
internal object AimiSettingsManifest {

    /**
     * Name prefixes of the AIMI keys that live in the shared key enums.
     *
     * `OApsxdrip` is in the list for one key, the AIMI one minute xDrip switch on the lab screen.
     */
    private val AIMI_NAME_PREFIXES = listOf("OApsAIMI", "Aimi", "OApsxdrip")

    /**
     * AIMI keys of the shared enums whose name follows no AIMI prefix, named one by one.
     *
     * The two max basal values belong to AIMI alone and are read in the AIMI dosing paths only.
     * The six context keys carry `aimi_context_*` storage keys and belong to the AIMI context
     * module.
     */
    private val EXTRA_SCOPED_KEY_NAMES = setOf(
        "autodriveMaxBasal",
        "meal_modes_MaxBasal",
        "ContextMode",
        "ContextLLMProvider",
        "ContextLLMOpenAIKey",
        "ContextLLMGeminiKey",
        "ContextLLMDeepSeekKey",
        "ContextLLMClaudeKey",
    )

    /**
     * Keys that look AIMI shaped but belong to another plugin, with the reason.
     *
     * A test walks the shared enums for anything whose name or storage key mentions AIMI, so a
     * key can only stay out of scope by being named here. Nothing is excluded by a prefix
     * accident.
     *
     * - `OverviewShowHybridDashboardAimiPulse`: an overview display switch. It says whether the
     *   dashboard draws the AIMI pulse ring; it changes no AIMI decision and the overview plugin
     *   owns it.
     */
    private val NOT_AIMI_KEY_NAMES = setOf(
        "OverviewShowHybridDashboardAimiPulse",
    )

    /**
     * Keys in scope with no entry in [entries].
     *
     * Empty: the whole inventory was classified in one pass. The set stays because the mechanism
     * is what stops the next regression, not because we expect to need it.
     */
    val UNCLASSIFIED: Set<String> = emptySet()

    /**
     * Classified keys that no settings section offers today.
     *
     * This is measured debt, not a design. Every key here is a key the person cannot reach, so a
     * stale value cannot be corrected from the app. Keys that only code owns (their [writers] hold
     * `LOOP` and nothing else) are runtime state and are never in this list, because they must not be
     * on a screen at all. A key the loop writes AND the person owns stays in scope: losing its screen
     * is exactly the failure this list is here to count.
     */
    val REACHABILITY_DEBT: Set<String> = setOf(
        DoubleKey.OApsAIMIActivityBasalCapFactor.key,
        BooleanKey.OApsAIMIAimiSmbComparatorEnabled.key,
        DoubleKey.OApsAIMIAutodriveAcceleration.key,
        BooleanKey.OApsAIMIAutodriveV3EnhancedGater.key,
        BooleanKey.OApsAIMIDiaGovernorEnabled.key,
        DoubleKey.OApsAIMIDiaGovernorLearnedWeight.key,
        BooleanKey.OApsAIMIEffectiveIobReleaseEnabled.key,
        BooleanKey.OApsAIMIEnableBasal.key,
        BooleanKey.OApsAIMIEnableStepsFromWatch.key,
        DoubleKey.OApsAIMIFCLFactor.key,
        IntKey.OApsAIMIHighBGinterval.key,
        BooleanKey.OApsAIMIHyperDroppingExemptEnabled.key,
        BooleanKey.OApsAIMIIntelligenceKineticsProfiler.key,
        BooleanKey.OApsAIMIIntelligenceSingleLearnPath.key,
        BooleanKey.OApsAIMIIntelligenceSnapshotExport.key,
        IntKey.OApsAIMIIntratickStallSeconds.key,
        BooleanKey.OApsAIMILoopBlackboxFileEnabled.key,
        BooleanKey.OApsAIMILoopExclusiveInvocationEnabled.key,
        BooleanKey.OApsAIMIMealHyperBypassEnabled.key,
        DoubleKey.OApsAIMINightGrowthBasalMultiplier.key,
        DoubleKey.OApsAIMINightGrowthMaxSmbClamp.key,
        DoubleKey.OApsAIMINightGrowthMinRiseSlope.key,
        DoubleKey.OApsAIMINightGrowthSmbMultiplier.key,
        BooleanKey.OApsAIMIPkpdEndogenousReversion.key,
        BooleanKey.OApsAIMIPkpdHyperReversion.key,
        BooleanKey.OApsAIMIPkpdPredictionKinetics.key,
        DoubleKey.OApsAIMIPlateauBandAbs.key,
        BooleanKey.OApsAIMIPredictionAuthorityEnabled.key,
        BooleanKey.OApsAIMIPredictionAuthorityShadow.key,
        DoubleKey.OApsAIMIR2Confident.key,
        BooleanKey.OApsAIMIT3cPhysioInformedEnabled.key,
        BooleanKey.OApsAIMIforcelimits.key,
        // Adaptive Kernel Bank (Cosine Gate): no screen exists for any of its six keys today.
        BooleanKey.AimiCosineGateEnabled.key,
        DoubleKey.AimiCosineGateAlpha.key,
        DoubleKey.AimiCosineGateMinDataQuality.key,
        DoubleKey.AimiCosineGateMinSensitivity.key,
        DoubleKey.AimiCosineGateMaxSensitivity.key,
        IntKey.AimiCosineGateMaxPeakShift.key,
        // Read every loop tick (unannounced-meal confidence), never shown on a screen.
        DoubleKey.AimiUamConfidence.key,
        // Read by the physio LLM analyzer, never shown on a screen.
        StringKey.AimiPhysioLLMProvider.key,
        // Set from `AimiProfileAdvisorActivity`'s button row, which is a standalone activity, not
        // a section of the AIMI settings tree this manifest covers.
        StringKey.AimiTuningContextSelection.key,
        // The context module's settings live in their own activity, not in the settings tree this
        // manifest models. Naming them here is honest; giving them a section would claim a
        // reachability the tree does not provide.
        StringKey.ContextMode.key,
        StringKey.ContextLLMProvider.key,
        StringKey.ContextLLMOpenAIKey.key,
        StringKey.ContextLLMGeminiKey.key,
        StringKey.ContextLLMDeepSeekKey.key,
        StringKey.ContextLLMClaudeKey.key,
    )

    /**
     * Keys nothing reads and nothing writes anywhere in the app, found by a repo-wide grep for
     * each key's enum name outside this file, the key enum that declares it, and the user manual
     * strings that mention it as documentation.
     *
     * They are deletion candidates, not settings, so they need no screen and no [AimiSettingEntry].
     * This set does not shrink by re-classifying a key: it shrinks only when the key is deleted
     * from its key enum, at which point the completeness test's scope no longer mentions it either.
     */
    val DEAD_KEYS: Set<String> = setOf(
        // Superseded by inference from the WCycle contraceptive setting; see
        // `wcycle/EndometriosisAdjuster.kt`'s "HARMONIZATION" comment.
        BooleanKey.AimiEndometriosisHormonalSuppression.key,
        DoubleKey.OApsAIMIKickerStep.key,
        DoubleKey.OApsAIMIKickerMinUph.key,
        DoubleKey.OApsAIMIZeroResumeFrac.key,
        DoubleKey.OApsAIMIAntiStallBias.key,
        DoubleKey.OApsAIMIDeltaPosRelease.key,
        IntKey.OApsAIMIKickerStartMin.key,
        IntKey.OApsAIMIKickerMaxMin.key,
        IntKey.OApsAIMIZeroResumeMin.key,
        IntKey.OApsAIMIZeroResumeMax.key,
        IntKey.OApsAIMINightGrowthMinDurationMin.key,
        IntKey.OApsAIMINightGrowthMinEventualOverTarget.key,
        IntKey.OApsAIMINightGrowthDecayMinutes.key,
        IntKey.OApsAIMIlogsize.key,
        IntKey.OApsAIMIAutodriveTarget.key,
        StringKey.OApsAIMIUnstableModeState.key,
        AimiLongKey.PregnancyDueDate.key,
    )

    /** Every AIMI setting, exactly once. */
    val entries: List<AimiSettingEntry> = listOf(
        AimiSettingEntry(BooleanKey.OApsAIMIMLtraining, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIEnableBasal, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIEnableStepsFromWatch, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIpregnancy, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIforcelimits, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMInight, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIhoneymoon, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(BooleanKey.OApsAIMIT3cPhysioInformedEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIAutodriveV3EnhancedGater, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIautoDriveActive, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(BooleanKey.OApsAIMIautodriveAggressiveSmbFloor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIStressIsfFloor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIAnticipBasalFloor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIAnticipMealEvidence, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIRiseCeilingGuard, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIEffortActivityProtection, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIautoDriveAuthoritative, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(BooleanKey.OApsAIMIwcycle, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIWCycleShadow, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIWCycleRequireConfirm, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMINightGrowthEnabled, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdEnabled, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        // BOOKKEEPING, not USER: the wizard UI sets this flag when it finishes or is skipped
        // (`PkpdSettingsUi.kt`). The person never types it and never sees it, so it needs no
        // screen; see the BOOKKEEPING KDoc on `AimiSettingsWriter`.
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdSetupWizardCompleted, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.BOOKKEEPING),
        AimiSettingEntry(BooleanKey.OApsAIMIPeakGovernorEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIIntelligenceSnapshotExport, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIIntelligenceSingleLearnPath, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIDiaGovernorEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIIntelligenceKineticsProfiler, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPredictionAuthorityShadow, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPredictionAuthorityEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIMealHyperBypassEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITreeMealRiseFrontLoad, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMISensorConfidenceCgmFirst, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIMealConfirmedEarlyRelease, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIHyperDroppingExemptEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.LOOP),
        AimiSettingEntry(BooleanKey.OApsAIMILoopBlackboxFileEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMILoopExclusiveInvocationEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIAimiSmbComparatorEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIIobSurveillanceGuard, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIEffectiveIobReleaseEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIHyperTrajectoryRelease, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(BooleanKey.OApsAIMIRecursiveBeliefShadow, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIRecursiveBeliefAuthority, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(BooleanKey.OApsAIMIRecursiveBeliefWavelet, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(BooleanKey.OApsAIMIDynIsfTrajectoryShadowOnly, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIUnifiedReactivityEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIAuditorProfileFactors, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITrajectoryGuardEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.LOOP),
        AimiSettingEntry(BooleanKey.OApsAIMIContextEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIBasalSlewLimitEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdEndogenousReversion, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdHyperReversion, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdStackAwareGuardB, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdCurveLearningGate, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIBasalChannelSafetyGuards, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIBasalProjectedError, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIBasalTerminalInvariants, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITrajBridgeBasalSurvives, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITubeVetoIgnoreFloorArtefact, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITubeHyperClampPhysicalBound, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIPkpdPredictionKinetics, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIContextLLMEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIT3cBrittleMode, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIT3cAutodriveBasalAuthority, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIT3cHyperBasalFloor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIT3cCfrdMode, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIT3cCfrdExacerbationMode, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIUndeclaredCobEnabled, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIThyroidEnabled, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIThyroidLogVerbosity, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIMealAdvisorTrigger, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(BooleanKey.OApsAIMIAdvisorPersonalOrefMl, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMIAdvisorLlmRichOref, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITpoEnabled, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITpoLlmConfirmEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.OApsAIMITpoNotifyOnApply, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIMaxSMB, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIHighBGMaxSMB, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIweight, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMICHO, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMITDD7, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdInitialDiaH, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdInitialPeakMin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdAnchorDiaH, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdAnchorPeakMin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdBoundsDiaMinH, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.PRESET),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdStateDiaH, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdStatePeakMin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdStatePriorPeak, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdStatePhysioPeak, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdStateSitePeak, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdStateTrajectoryPeak, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdStateEffectivePeak, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPeakGovernorLearnedWeight, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIDiaGovernorLearnedWeight, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIIsfFusionMinFactor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIIsfFusionMaxFactor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMISmbTailThreshold, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMISmbTailDamping, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMISmbExerciseDamping, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMISmbLateFatDamping, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIRedCarpetRestoreThreshold, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPriorityMaxIobFactor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIPriorityMaxIobExtraU, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIMealFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIFCLFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIBFFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIBFPrebolus, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIBFPrebolus2, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMILunchFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIDinnerFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIHCFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMISnackFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIsleepFactor, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIAnticipBudgetU, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIMealPrebolus, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIautodrivePrebolus, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIautodrivesmallPrebolus, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIcombinedDelta, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIAutodriveDeviation, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIAutodriveAcceleration, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMILunchPrebolus, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMILunchPrebolus2, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIDinnerPrebolus, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIDinnerPrebolus2, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMISnackPrebolus, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIHighCarbPrebolus, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIHighCarbPrebolus2, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIwcycledateday, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIWCycleClampMin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIWCycleClampMax, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMINightGrowthMinRiseSlope, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMINightGrowthSmbMultiplier, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMINightGrowthBasalMultiplier, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMINightGrowthMaxSmbClamp, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMINightGrowthMaxIobExtra, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIActivityBasalCapFactor, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIHighBg, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIHyperEstablishedDevMgdl, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIHyperDeepDevMgdl, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.SLIDER),
        AimiSettingEntry(DoubleKey.OApsAIMIPlateauBandAbs, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIR2Confident, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIMaxMultiplier, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMILastEstimatedCarbs, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMILastEstimatedCarbTime, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(DoubleKey.OApsAIMIT3cActivationThreshold, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIT3cAnticipationStrength, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIT3cAggressiveness, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIT3cCfrdCobDelayMin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIUndeclaredCobMaxG, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIAdaptiveBasalMaxScaling, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHypoRateEnter, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHypoRateExit, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHypoBgMgdl, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceSevereHypoBgMgdl, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldBasalFloorRate, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldBasalDecayRate, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldAggFloorRate, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldAggDecayRate, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldBasalFloorSevere, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldBasalDecaySevere, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldAggFloorSevere, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceHoldAggDecaySevere, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceAnticipationLookbackSamples, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceAnticipationMarginMgdl, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceAnticipationHypoDamp, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.OApsAIMIGovernanceAnticipationDecayBlendMax, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMIHighBGinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMImealinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMILunchinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMIDinnerinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMIHCinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMISnackinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMIBFinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMISleepinterval, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMIAutodriveBG, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMIWCycleAvgLength, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMINightGrowthAgeYears, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.OApsAIMIIntratickStallSeconds, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIWCycleTrackingMode, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIWCycleContraceptive, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIWCycleThyroid, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIWCycleVerneuil, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMINightGrowthStart, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMINightGrowthEnd, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIContextStorage, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIThyroidMode, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIThyroidManualStatus, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIThyroidTreatmentPhase, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.OApsAIMIThyroidGuardLevel, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(AimiStringKey.PregnancyDueDateString, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(AimiStringKey.RemoteControlPin, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(AimiStringKey.OuraPersonalAccessToken, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(AimiStringKey.ActivitySourceMode, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(AimiStringKey.OApsAIMIPkpdStateDominantBranch, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.LOOP),
        AimiSettingEntry(AimiStringKey.OApsAIMIPkpdLastPeakGovLogLine, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.LOOP),
        AimiSettingEntry(AimiStringKey.OApsAIMIPkpdLastPeakGovConsoleEchoed, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.LOOP),
        AimiSettingEntry(AimiLongKey.LastPrebolusTime, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(AimiLongKey.LastLegacyPrebolusTime, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(AimiLongKey.PendingLegacyPrebolusUnitMilli, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),
        AimiSettingEntry(AimiLongKey.PendingLegacyPrebolusExpiry, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.LOOP),

        // --- Widened scope batch (2026-10-03): keys outside the OApsAIMI prefix. ---
        // AimiEndometriosisHormonalSuppression is NOT here: see DEAD_KEYS.
        AimiSettingEntry(BooleanKey.OApsxdriponeminute, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiAuditorEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiPhysioAssistantEnable, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiPhysioSleepDataEnable, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiPhysioHRVDataEnable, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiPhysioLLMAnalysisEnable, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiPhysioDebugLogs, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiEndometriosisEnable, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiEndometriosisPainFlare, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(BooleanKey.AimiCosineGateEnabled, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(
            BooleanKey.AimiEmergencySosEnable, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER
        ),
        AimiSettingEntry(DoubleKey.AimiUamConfidence, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.MealCapture, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.AimiEndometriosisBasalMult, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.AimiEndometriosisSmbDampen, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.AimiCosineGateAlpha, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.AimiCosineGateMinDataQuality, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.AimiCosineGateMinSensitivity, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.AimiCosineGateMaxSensitivity, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        // Also rewritten by the transient preference overlay: `tpo/TpoPreferenceKeys.kt`.
        AimiSettingEntry(
            DoubleKey.AimiTubeHypoFloorMgdl, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability,
            AimiSettingsWriter.USER, AimiSettingsWriter.LOOP,
        ),
        AimiSettingEntry(DoubleKey.AimiTubeHyperBandMgdl, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        // Also rewritten by the transient preference overlay: `tpo/TpoPreferenceKeys.kt`.
        AimiSettingEntry(
            DoubleKey.AimiTubeAggressiveness, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability,
            AimiSettingsWriter.USER, AimiSettingsWriter.LOOP,
        ),
        AimiSettingEntry(DoubleKey.AimiTubeBasalTrimMax, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(DoubleKey.AimiTubeKappaSafetyMargin, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.AimiAuditorMaxPerHour, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.AimiAuditorTimeoutSeconds, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.AimiAuditorMinConfidence, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.AimiEndometriosisFlareDuration, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.AimiCosineGateMaxPeakShift, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Stability, AimiSettingsWriter.USER),
        AimiSettingEntry(IntKey.AimiEmergencySosThreshold, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER),
        AimiSettingEntry(
            IntKey.AimiEmergencySosImmediateThreshold, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER
        ),
        AimiSettingEntry(
            IntKey.AimiEmergencySosStaleThreshold, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, AimiSettingsWriter.USER
        ),
        AimiSettingEntry(StringKey.AimiAdvisorProvider, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.AimiTuningContextSelection, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.AimiAuditorMode, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        AimiSettingEntry(StringKey.AimiPhysioLLMProvider, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Physio, AimiSettingsWriter.USER),
        // Secret: an API key typed once and never shown again. See `AimiSettingEntry.secret`.
        // The two real maximum-basal ceilings. They carry no `OApsAIMI` prefix, which is exactly why
        // they escaped the guarantee until `isAimiScoped` was taught to read its own named list. Both
        // are already on a running screen, so SIMPLE costs nothing here and says the truth: a ceiling
        // on insulin is the person's to set.
        AimiSettingEntry(DoubleKey.autodriveMaxBasal, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(DoubleKey.meal_modes_MaxBasal, AimiSettingsLevel.SIMPLE, AimiBehaviorFamilyId.Protection, setOf(AimiSettingsWriter.USER)),
        // The context module's own provider settings. Their screen is a standalone activity outside
        // the settings tree this manifest models, so they sit in the reachability debt rather than
        // claim a home they do not have.
        AimiSettingEntry(StringKey.ContextMode, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.ContextLLMProvider, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.ContextLLMOpenAIKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.ContextLLMGeminiKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.ContextLLMDeepSeekKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.ContextLLMClaudeKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.AimiAdvisorOpenAIKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.AimiAdvisorGeminiKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.AimiAdvisorDeepSeekKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(StringKey.AimiAdvisorClaudeKey, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, setOf(AimiSettingsWriter.USER)),
        AimiSettingEntry(AimiStringKey.AimiAdvisorClaudeModel, AimiSettingsLevel.EXPERT, AimiBehaviorFamilyId.Autonomy, AimiSettingsWriter.USER),
        // Secret: a personal phone number, not a dosing value. See `AimiSettingEntry.secret`.
        AimiSettingEntry(StringKey.AimiEmergencySosPhone, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, setOf(AimiSettingsWriter.USER), declaredSecret = true),
        AimiSettingEntry(StringKey.AimiEmergencySosPhone2, AimiSettingsLevel.ADVANCED, AimiBehaviorFamilyId.Protection, setOf(AimiSettingsWriter.USER), declaredSecret = true),
    )

    private val entryByKeyString: Map<String, AimiSettingEntry> =
        entries.associateBy { it.key.key }

    /** Every key the manifest must cover. See the scope note in the class documentation. */
    val scopedKeys: List<NonPreferenceKey> = buildList {
        addAll(BooleanKey.entries.filter { it.isAimiScoped() })
        addAll(DoubleKey.entries.filter { it.isAimiScoped() })
        addAll(IntKey.entries.filter { it.isAimiScoped() })
        addAll(StringKey.entries.filter { it.isAimiScoped() })
        addAll(AimiStringKey.entries)
        addAll(AimiLongKey.entries)
    }

    /**
     * Is this key AIMI's, and therefore bound by the manifest?
     *
     * The two named lists come FIRST on purpose. They used to be documented here and never read, so
     * `autodriveMaxBasal` and `meal_modes_MaxBasal` — the real maximum-basal settings — sat outside the
     * guarantee while the KDoc said they were inside it. A prefix is a convenience; a name in a list is
     * a decision, and a decision wins.
     */
    private fun Enum<*>.isAimiScoped(): Boolean = when {
        name in NOT_AIMI_KEY_NAMES   -> false
        name in EXTRA_SCOPED_KEY_NAMES -> true
        else                         -> AIMI_NAME_PREFIXES.any { name.startsWith(it) }
    }

    fun entryFor(key: NonPreferenceKey): AimiSettingEntry? = entryByKeyString[key.key]

    fun entryForKeyString(key: String): AimiSettingEntry? = entryByKeyString[key]

    fun levelOf(key: NonPreferenceKey): AimiSettingsLevel? = entryFor(key)?.level

    /**
     * Everyone allowed to write this key, or null when the key is not in the manifest.
     *
     * A set, not one value: a key can be typed by the person AND rewritten by the loop. The future
     * audit screen needs both facts to say "the loop changed this" instead of blaming the person.
     */
    fun writersOf(key: NonPreferenceKey): Set<AimiSettingsWriter>? = entryFor(key)?.writers

    /** True when the loop may write this key from inside a tick. */
    fun loopWrites(key: NonPreferenceKey): Boolean =
        entryFor(key)?.writers?.contains(AimiSettingsWriter.LOOP) == true

    fun keysAtLevel(level: AimiSettingsLevel): List<NonPreferenceKey> =
        entries.filter { it.level == level }.map { it.key }
}
