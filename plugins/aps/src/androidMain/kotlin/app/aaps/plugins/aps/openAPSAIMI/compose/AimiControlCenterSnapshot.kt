package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.TextRef
import androidx.annotation.StringRes
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeTickRecord
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeTickStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistoryReader
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeTickRecord
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeTickStatus
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import java.util.Locale
import kotlin.math.abs

internal enum class AimiBehaviorFamilyId {
    Protection,
    MealCapture,
    Stability,
    Physio,
    Autonomy,
}

internal data class AimiControlCenterSnapshot(
    val families: List<AimiBehaviorFamilySnapshot>,
    val contextSection: AimiControlSectionSnapshot,
    val sourceSection: AimiControlSectionSnapshot,
)

internal data class AimiBehaviorFamilySnapshot(
    val id: AimiBehaviorFamilyId,
    @StringRes val titleResId: Int,
    @StringRes val questionResId: Int,
    @StringRes val leftAnchorResId: Int,
    @StringRes val rightAnchorResId: Int,
    @StringRes val levelLabelResId: Int,
    val normalizedScore: Float,
    val confidence: Float,
    val managedPreferenceCount: Int,
    val expertPreferenceCount: Int,
    val status: AimiProjectionStatus,
    val details: List<AimiControlDetail>,
    val t3cRuntime: AimiT3cRuntimeSnapshot? = null,
    val harmoniaRuntime: AimiHarmoniaRuntimeSnapshot? = null,
)

internal data class AimiControlSectionSnapshot(
    @StringRes val titleResId: Int,
    @StringRes val summaryResId: Int,
    val details: List<AimiControlDetail>,
)

internal data class AimiControlDetail(
    @StringRes val titleResId: Int,
    val valueText: String? = null,
    @StringRes val valueResId: Int? = null,
)

internal enum class AimiProjectionStatus(@StringRes val labelResId: Int) {
    CoherentProfile(R.string.aimi_control_center_coherent_profile),
    MixedLegacy(R.string.aimi_control_center_mixed_legacy),
    ExpertPersonalized(R.string.aimi_control_center_expert_personalized),
}

internal enum class AimiT3cRuntimeStatus(@StringRes val labelResId: Int) {
    NativeApplied(R.string.aimi_control_center_t3c_status_native_applied),
    NativeReady(R.string.aimi_control_center_t3c_status_native_ready),
    NativeBlocked(R.string.aimi_control_center_t3c_status_native_blocked),
    LegacyFallback(R.string.aimi_control_center_t3c_status_legacy_fallback),
    SafetyTerminal(R.string.aimi_control_center_t3c_status_safety_terminal),
    Unavailable(R.string.aimi_control_center_t3c_status_unavailable),
}

internal enum class AimiT3cRuntimeOwner(@StringRes val labelResId: Int) {
    NativeRbt(R.string.aimi_control_center_t3c_owner_native),
    LegacyBypass(R.string.aimi_control_center_t3c_owner_legacy),
    SafetyGate(R.string.aimi_control_center_t3c_owner_safety),
    Unavailable(R.string.aimi_control_center_t3c_owner_unavailable),
}

internal data class AimiT3cRuntimeSnapshot(
    val status: AimiT3cRuntimeStatus,
    val owner: AimiT3cRuntimeOwner,
    val modeText: String,
    val authorityApplied: Boolean,
    val shadowOnly: Boolean,
    val details: List<AimiControlDetail>,
)

internal enum class AimiHarmoniaRuntimeStatus(@StringRes val labelResId: Int) {
    NativeApplied(R.string.aimi_control_center_harmonia_status_native_applied),
    NativeReady(R.string.aimi_control_center_harmonia_status_native_ready),
    NativeBlocked(R.string.aimi_control_center_harmonia_status_native_blocked),
    T3cPriority(R.string.aimi_control_center_harmonia_status_t3c_priority),
    Unavailable(R.string.aimi_control_center_harmonia_status_unavailable),
}

internal data class AimiHarmoniaRuntimeSnapshot(
    val status: AimiHarmoniaRuntimeStatus,
    val productionModeText: String,
    val active: Boolean,
    val eligible: Boolean,
    val selectedForProduction: Boolean,
    val addsSmbAuthority: Boolean,
    val details: List<AimiControlDetail>,
)

internal fun buildAimiControlCenterSnapshot(
    preferences: Preferences,
    t3cRuntime: AimiT3cRuntimeSnapshot? = null,
    harmoniaRuntime: AimiHarmoniaRuntimeSnapshot? = null,
): AimiControlCenterSnapshot =
    AimiControlCenterSnapshot(
        families = listOf(
            buildProtectionFamily(preferences),
            buildMealCaptureFamily(preferences),
            buildStabilityFamily(preferences, t3cRuntime),
            buildPhysioFamily(preferences, harmoniaRuntime),
            buildAutonomyFamily(preferences),
        ),
        contextSection = buildContextSection(preferences),
        sourceSection = buildSourceSection(preferences),
    )

private fun buildProtectionFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    val scores = listOf(
        normalize(preferences.get(DoubleKey.OApsAIMIMaxSMB), DoubleKey.OApsAIMIMaxSMB),
        normalize(preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), DoubleKey.OApsAIMIHighBGMaxSMB),
        normalize(preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor), DoubleKey.OApsAIMIPriorityMaxIobFactor),
        normalize(preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU), DoubleKey.OApsAIMIPriorityMaxIobExtraU),
        normalize(preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor), DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor),
        normalize(preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold), DoubleKey.OApsAIMIRedCarpetRestoreThreshold),
    )
    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        id = AimiBehaviorFamilyId.Protection,
        titleResId = R.string.aimi_control_center_protection_title,
        questionResId = R.string.aimi_control_center_protection_question,
        leftAnchorResId = R.string.aimi_control_center_protection_left,
        rightAnchorResId = R.string.aimi_control_center_protection_right,
        levelLabelResId = protectionLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Protection),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Protection),
        status = projection.status,
        details = listOf(
            detail(R.string.openapsaimi_maxsmb_title, preferences.get(DoubleKey.OApsAIMIMaxSMB), "U"),
            detail(R.string.openapsaimi_highBG_maxsmb_title, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), "U"),
            detail(R.string.oaps_aimi_priority_max_iob_factor_title, preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor), "x"),
            detail(R.string.oaps_aimi_priority_max_iob_extra_title, preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU), "U"),
            detail(R.string.oaps_aimi_pkpd_relief_factor_title, preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor), null),
            detail(R.string.oaps_aimi_redcarpet_restore_title, preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold), null),
        ),
    )
}

private fun buildMealCaptureFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    val hyperTrajectory = preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease)
    val aggressiveTrajectory = hyperTrajectory && preferences.get(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive)
    val autodriveMaxBasal = preferences.get(DoubleKey.autodriveMaxBasal)
    val mealModesMaxBasal = preferences.get(DoubleKey.meal_modes_MaxBasal)

    val scores = mutableListOf<Float>()
    scores += boolScore(hyperTrajectory, whenFalse = 0.28f, whenTrue = 0.68f)
    scores += boolScore(aggressiveTrajectory, whenFalse = 0.46f, whenTrue = 0.88f)
    scores += mealBasalCapScore(autodriveMaxBasal)
    scores += mealBasalCapScore(mealModesMaxBasal)
    scores += normalize(preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep), DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep)
    scores += normalize(preferences.get(DoubleKey.OApsAIMIautodrivePrebolus), DoubleKey.OApsAIMIautodrivePrebolus)
    scores += normalize(preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus), DoubleKey.OApsAIMIautodrivesmallPrebolus)
    scores += inverseNormalize(preferences.get(DoubleKey.OApsAIMIHyperEstablishedDevMgdl), DoubleKey.OApsAIMIHyperEstablishedDevMgdl)
    scores += inverseNormalize(preferences.get(DoubleKey.OApsAIMIHyperDeepDevMgdl), DoubleKey.OApsAIMIHyperDeepDevMgdl)

    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        id = AimiBehaviorFamilyId.MealCapture,
        titleResId = R.string.aimi_control_center_meal_title,
        questionResId = R.string.aimi_control_center_meal_question,
        leftAnchorResId = R.string.aimi_control_center_meal_left,
        rightAnchorResId = R.string.aimi_control_center_meal_right,
        levelLabelResId = mealLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.MealCapture),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.MealCapture),
        status = projection.status,
        details = listOf(
            boolDetail(BooleanKey.OApsAIMIHyperTrajectoryRelease, hyperTrajectory),
            boolDetail(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, aggressiveTrajectory),
            detail(DoubleKey.autodriveMaxBasal, autodriveMaxBasal, "U/h"),
            detail(DoubleKey.meal_modes_MaxBasal, mealModesMaxBasal, "U/h"),
            detail(R.string.aimi_mpc_u_per_kg_title, preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep), "U/kg/5m"),
            detail(R.string.prebolus_autodrive_mode_title, preferences.get(DoubleKey.OApsAIMIautodrivePrebolus), "U"),
            detail(R.string.prebolussmall_autodrive_mode_title, preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus), "U"),
            detail(DoubleKey.OApsAIMIHyperEstablishedDevMgdl, preferences.get(DoubleKey.OApsAIMIHyperEstablishedDevMgdl), "mg/dL"),
            detail(DoubleKey.OApsAIMIHyperDeepDevMgdl, preferences.get(DoubleKey.OApsAIMIHyperDeepDevMgdl), "mg/dL"),
        ),
    )
}

private fun buildStabilityFamily(
    preferences: Preferences,
    t3cRuntime: AimiT3cRuntimeSnapshot?,
): AimiBehaviorFamilySnapshot {
    val dynIsfEnabled = preferences.get(BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled)
    val adaptiveBasalEnabled = preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
    val scores = listOf(
        // Tail floor: score on PKPD band (not 0–1 pref span). Higher floor = less damping = more reactive.
        // Also applies effectiveStoredValue so legacy ≤0.55 reads as neutral, not "ultra-smooth".
        PkpdSmbTailDamping.stabilityFamilyScore(preferences.get(DoubleKey.OApsAIMISmbTailDamping)),
        normalize(preferences.get(DoubleKey.OApsAIMISmbExerciseDamping), DoubleKey.OApsAIMISmbExerciseDamping),
        normalize(preferences.get(DoubleKey.OApsAIMISmbLateFatDamping), DoubleKey.OApsAIMISmbLateFatDamping),
        boolScore(adaptiveBasalEnabled, whenFalse = 0.35f, whenTrue = 0.66f),
        boolScore(dynIsfEnabled, whenFalse = 0.32f, whenTrue = 0.72f),
        normalize(preferences.get(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction), DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction),
    )
    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        id = AimiBehaviorFamilyId.Stability,
        titleResId = R.string.aimi_control_center_stability_title,
        questionResId = R.string.aimi_control_center_stability_question,
        leftAnchorResId = R.string.aimi_control_center_stability_left,
        rightAnchorResId = R.string.aimi_control_center_stability_right,
        levelLabelResId = stabilityLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Stability),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Stability),
        status = projection.status,
        details = listOf(
            // Show the value the loop actually uses (legacy ≤0.55 → neutral), so the row matches the slider.
            detail(
                R.string.oaps_aimi_smb_tail_damping_title,
                PkpdSmbTailDamping.effectiveStoredValue(preferences.get(DoubleKey.OApsAIMISmbTailDamping)),
                null,
            ),
            detail(R.string.oaps_aimi_smb_exercise_damping_title, preferences.get(DoubleKey.OApsAIMISmbExerciseDamping), null),
            detail(R.string.oaps_aimi_smb_late_fat_damping_title, preferences.get(DoubleKey.OApsAIMISmbLateFatDamping), null),
            boolDetail(R.string.oaps_aimi_adaptive_basal_title, adaptiveBasalEnabled),
            boolDetail(BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, dynIsfEnabled),
            detail(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, preferences.get(DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction), null),
        ),
        t3cRuntime = t3cRuntime,
    )
}

private fun buildPhysioFamily(
    preferences: Preferences,
    harmoniaRuntime: AimiHarmoniaRuntimeSnapshot?,
): AimiBehaviorFamilySnapshot {
    val assistantEnabled = preferences.get(BooleanKey.AimiPhysioAssistantEnable)
    val sleepEnabled = preferences.get(BooleanKey.AimiPhysioSleepDataEnable)
    val hrvEnabled = preferences.get(BooleanKey.AimiPhysioHRVDataEnable)

    val scores = listOf(
        boolScore(assistantEnabled, whenFalse = 0.14f, whenTrue = 0.72f),
        boolScore(sleepEnabled, whenFalse = 0.20f, whenTrue = 0.60f),
        boolScore(hrvEnabled, whenFalse = 0.22f, whenTrue = 0.78f),
    )
    val projection = project(scores)
    return AimiBehaviorFamilySnapshot(
        id = AimiBehaviorFamilyId.Physio,
        titleResId = R.string.aimi_control_center_physio_title,
        questionResId = R.string.aimi_control_center_physio_question,
        leftAnchorResId = R.string.aimi_control_center_physio_left,
        rightAnchorResId = R.string.aimi_control_center_physio_right,
        levelLabelResId = physioLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Physio),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Physio),
        status = projection.status,
        details = listOf(
            boolDetail(R.string.aimi_physio_enable_title, assistantEnabled),
            boolDetail(R.string.aimi_physio_sleep_enable_title, sleepEnabled),
            boolDetail(R.string.aimi_physio_hrv_enable_title, hrvEnabled),
        ),
        harmoniaRuntime = harmoniaRuntime,
    )
}

private fun buildAutonomyFamily(preferences: Preferences): AimiBehaviorFamilySnapshot {
    // Classic (V1/V2) autodrive removed — the autonomy ladder is expressed via V3 + its sub-flags only.
    val autoDriveActive = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
    val hyperTrajectory = autoDriveActive && preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease)
    val authoritative = autoDriveActive && preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative)
    val recursiveAuthority = autoDriveActive && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
    val aggressiveSmbFloor = autoDriveActive && preferences.get(BooleanKey.OApsAIMIautodriveAggressiveSmbFloor)

    val levelLabelResId = when {
        !autoDriveActive -> R.string.aimi_control_center_autonomy_observation
        recursiveAuthority || authoritative -> R.string.aimi_control_center_autonomy_controlled
        hyperTrajectory -> R.string.aimi_control_center_autonomy_assisted
        else -> R.string.aimi_control_center_autonomy_recommendations
    }
    val score = when (levelLabelResId) {
        R.string.aimi_control_center_autonomy_observation -> 0.12f
        R.string.aimi_control_center_autonomy_recommendations -> 0.42f
        R.string.aimi_control_center_autonomy_assisted -> 0.72f
        else -> 0.95f
    }

    return AimiBehaviorFamilySnapshot(
        id = AimiBehaviorFamilyId.Autonomy,
        titleResId = R.string.aimi_control_center_autonomy_title,
        questionResId = R.string.aimi_control_center_autonomy_question,
        leftAnchorResId = R.string.aimi_control_center_autonomy_left,
        rightAnchorResId = R.string.aimi_control_center_autonomy_right,
        levelLabelResId = levelLabelResId,
        normalizedScore = score,
        confidence = 1.0f,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Autonomy),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Autonomy),
        status = AimiProjectionStatus.CoherentProfile,
        // HTR is surfaced in the Meal-capture family; avoid a duplicate detail-row title here.
        details = listOf(
            boolDetail(R.string.oaps_aimi_enableMlautoDriveActive_title, autoDriveActive),
            boolDetail(BooleanKey.OApsAIMIRecursiveBeliefAuthority, recursiveAuthority),
            boolDetail(BooleanKey.OApsAIMIautoDriveAuthoritative, authoritative),
            boolDetail(BooleanKey.OApsAIMIautodriveAggressiveSmbFloor, aggressiveSmbFloor),
        ),
    )
}

private fun buildContextSection(preferences: Preferences): AimiControlSectionSnapshot =
    AimiControlSectionSnapshot(
        titleResId = R.string.aimi_control_center_context_title,
        summaryResId = R.string.aimi_control_center_context_summary,
        details = listOf(
            detail(R.string.oaps_aimi_weight_title, preferences.get(DoubleKey.OApsAIMIweight), "kg"),
            detail(R.string.oaps_aimi_cho_title, preferences.get(DoubleKey.OApsAIMICHO), "g"),
            detail(R.string.oaps_aimi_tdd7_title, preferences.get(DoubleKey.OApsAIMITDD7), "U"),
            AimiControlDetail(
                titleResId = R.string.OApsAIMI_Enable_pregnancy,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIpregnancy)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.OApsAIMI_Enable_honeymoon,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIhoneymoon)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_cycle_module_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIwcycle)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.oaps_aimi_thyroid_enabled_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIThyroidEnabled)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.endo_enable_title,
                valueResId = if (preferences.get(BooleanKey.AimiEndometriosisEnable)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
            AimiControlDetail(
                titleResId = R.string.oaps_aimi_ngr_enabled_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMINightGrowthEnabled)) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        ),
    )

private fun buildSourceSection(preferences: Preferences): AimiControlSectionSnapshot {
    val sourceMode = preferences.get(AimiStringKey.ActivitySourceMode)
    // AimiStringKey.ActivitySourceMode.entries became Map<String, TextRef> with the wave 10
    // migration - TextRef.Named values, not resource ids, so they cannot answer this Int-typed
    // field. Same four options, same four resources, read directly instead of through the key.
    val sourceResId = when (sourceMode) {
        "prefer_wear" -> R.string.pref_aimi_steps_source_wear
        "auto" -> R.string.pref_aimi_steps_source_auto
        "hc_only" -> R.string.pref_aimi_steps_source_hc
        else -> R.string.pref_aimi_steps_source_disabled
    }
    val ouraConfigured = preferences.get(AimiStringKey.OuraPersonalAccessToken).isNotBlank()
    return AimiControlSectionSnapshot(
        titleResId = R.string.aimi_control_center_sources_title,
        summaryResId = R.string.aimi_control_center_sources_summary,
        details = listOf(
            AimiControlDetail(
                titleResId = R.string.pref_aimi_steps_source_title,
                valueText = sourceMode,
                valueResId = sourceResId,
            ),
            AimiControlDetail(
                titleResId = R.string.aimi_oura_pat_title,
                valueResId = if (ouraConfigured) R.string.aimi_control_center_configured else R.string.aimi_control_center_not_configured,
            ),
        ),
    )
}

internal fun loadLatestT3cRuntimeSnapshot(): AimiT3cRuntimeSnapshot {
    val tick = T3cRuntimeHistoryReader.readLatestTick() ?: return unavailableT3cRuntimeSnapshot()
    val status = when (tick.status) {
        T3cRuntimeTickStatus.NATIVE_APPLIED -> AimiT3cRuntimeStatus.NativeApplied
        T3cRuntimeTickStatus.NATIVE_READY -> AimiT3cRuntimeStatus.NativeReady
        T3cRuntimeTickStatus.NATIVE_BLOCKED -> AimiT3cRuntimeStatus.NativeBlocked
        T3cRuntimeTickStatus.LEGACY_FALLBACK -> AimiT3cRuntimeStatus.LegacyFallback
        T3cRuntimeTickStatus.SAFETY_TERMINAL -> AimiT3cRuntimeStatus.SafetyTerminal
        T3cRuntimeTickStatus.UNAVAILABLE -> AimiT3cRuntimeStatus.Unavailable
    }
    val owner = when (tick.ownershipCategory) {
        app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeOwnershipCategory.NATIVE -> AimiT3cRuntimeOwner.NativeRbt
        app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeOwnershipCategory.LEGACY -> AimiT3cRuntimeOwner.LegacyBypass
        app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeOwnershipCategory.SAFETY -> AimiT3cRuntimeOwner.SafetyGate
        app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeOwnershipCategory.UNAVAILABLE -> AimiT3cRuntimeOwner.Unavailable
    }

    val details = buildList {
        add(AimiControlDetail(R.string.aimi_control_center_t3c_mode, valueText = tick.mode))
        tick.basalDemandRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = R.string.aimi_control_center_t3c_basal_demand,
                    valueText = formatT3cBasalDemand(tick),
                ),
            )
        }
        tick.appliedRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = R.string.aimi_control_center_t3c_applied_rate,
                    valueText = formatT3cAppliedRate(tick),
                ),
            )
        }
        tick.blocker?.let { blocker ->
            add(
                AimiControlDetail(
                    titleResId = R.string.aimi_control_center_t3c_blocker,
                    valueText = blocker,
                ),
            )
        }
        add(
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_t3c_authority_applied,
                valueResId = if (tick.authorityApplied) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_t3c_shadow_only,
                valueResId = if (tick.shadowOnly) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_t3c_selected_for_production,
                valueResId = if (tick.selectedForProduction) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_t3c_bypass_neutralized,
                valueResId = if (tick.historicalBypassNeutralized) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        )
    }

    return AimiT3cRuntimeSnapshot(
        status = status,
        owner = owner,
        modeText = tick.mode,
        authorityApplied = tick.authorityApplied,
        shadowOnly = tick.shadowOnly,
        details = details,
    )
}

internal fun loadLatestHarmoniaRuntimeSnapshot(): AimiHarmoniaRuntimeSnapshot {
    val tick = HarmoniaRuntimeHistoryReader.readLatestTick() ?: return unavailableHarmoniaRuntimeSnapshot()
    val status = when (tick.status) {
        HarmoniaRuntimeTickStatus.NATIVE_APPLIED -> AimiHarmoniaRuntimeStatus.NativeApplied
        HarmoniaRuntimeTickStatus.NATIVE_READY -> AimiHarmoniaRuntimeStatus.NativeReady
        HarmoniaRuntimeTickStatus.NATIVE_BLOCKED -> AimiHarmoniaRuntimeStatus.NativeBlocked
        HarmoniaRuntimeTickStatus.T3C_PRIORITY -> AimiHarmoniaRuntimeStatus.T3cPriority
        HarmoniaRuntimeTickStatus.UNAVAILABLE -> AimiHarmoniaRuntimeStatus.Unavailable
    }
    val details = buildList {
        add(AimiControlDetail(R.string.aimi_control_center_harmonia_mode, valueText = tick.productionMode ?: "RBT"))
        tick.sourceAction?.let { action ->
            add(AimiControlDetail(R.string.aimi_control_center_harmonia_action, valueText = action))
        }
        tick.branch?.let { branch ->
            add(AimiControlDetail(R.string.aimi_control_center_harmonia_branch, valueText = branch))
        }
        tick.basalDemandRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = R.string.aimi_control_center_harmonia_basal_demand,
                    valueText = formatHarmoniaBasalDemand(tick),
                ),
            )
        }
        tick.appliedRateUph?.let {
            add(
                AimiControlDetail(
                    titleResId = R.string.aimi_control_center_harmonia_applied_rate,
                    valueText = formatHarmoniaAppliedRate(tick),
                ),
            )
        }
        if (tick.smbEligible || tick.smbAppliedToRbtDemand || tick.targetSmbU != null || tick.smbBlocker != null) {
            add(
                AimiControlDetail(
                    titleResId = R.string.aimi_control_center_harmonia_smb_channel,
                    valueText = when {
                        tick.smbAppliedToRbtDemand && tick.smbReducesRbtDemand -> "REDUCED_RBT_DEMAND"
                        tick.smbAppliedToRbtDemand -> "APPLIED_TO_RBT_DEMAND"
                        tick.smbEligible -> "READY"
                        tick.smbBlocker != null -> "BLOCKED"
                        else -> "OBSERVED"
                    },
                ),
            )
            tick.targetSmbU?.let {
                add(
                    AimiControlDetail(
                        titleResId = R.string.aimi_control_center_harmonia_smb_demand,
                        valueText = formatHarmoniaSmbDemand(tick),
                    ),
                )
            }
            tick.smbBlocker?.let { blocker ->
                add(AimiControlDetail(R.string.aimi_control_center_harmonia_smb_blocker, valueText = blocker))
            }
        }
        tick.blocker?.let { blocker ->
            add(AimiControlDetail(R.string.aimi_control_center_harmonia_blocker, valueText = blocker))
        }
        tick.basalFirstChannel?.let { channel ->
            add(AimiControlDetail(R.string.aimi_control_center_harmonia_basal_first_channel, valueText = channel))
        }
        add(
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_harmonia_selected_for_production,
                valueResId = if (tick.selectedForProduction) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        )
        add(
            AimiControlDetail(
                titleResId = R.string.aimi_control_center_harmonia_adds_smb_authority,
                valueResId = if (tick.addsSmbAuthority) CoreUiR.string.yes else CoreUiR.string.no,
            ),
        )
    }

    return AimiHarmoniaRuntimeSnapshot(
        status = status,
        productionModeText = tick.productionMode ?: "RBT",
        active = tick.active,
        eligible = tick.eligible,
        selectedForProduction = tick.selectedForProduction,
        addsSmbAuthority = tick.addsSmbAuthority,
        details = details,
    )
}

private fun formatT3cBasalDemand(tick: T3cRuntimeTickRecord): String {
    val demand = formatControlCenterDoubleValue(tick.basalDemandRateUph ?: 0.0, "U/h")
    val bounded = formatControlCenterDoubleValue(tick.boundedRateUph ?: tick.basalDemandRateUph ?: 0.0, "U/h")
    return "$demand -> $bounded"
}

private fun formatT3cAppliedRate(tick: T3cRuntimeTickRecord): String {
    val appliedRate = tick.appliedRateUph ?: return ""
    val rateText = formatControlCenterDoubleValue(appliedRate, "U/h")
    val appliedDuration = tick.appliedDurationMin
    return if (appliedDuration != null) "$rateText / ${appliedDuration}m" else rateText
}

private fun formatHarmoniaBasalDemand(tick: HarmoniaRuntimeTickRecord): String {
    val demand = formatControlCenterDoubleValue(tick.basalDemandRateUph ?: 0.0, "U/h")
    val bounded = formatControlCenterDoubleValue(tick.boundedRateUph ?: tick.basalDemandRateUph ?: 0.0, "U/h")
    val cap = tick.maxBasalCapUph?.let { " cap ${formatControlCenterDoubleValue(it, "U/h")}" }.orEmpty()
    return "$demand -> $bounded$cap"
}

private fun formatHarmoniaAppliedRate(tick: HarmoniaRuntimeTickRecord): String {
    val appliedRate = tick.appliedRateUph ?: return ""
    val rateText = formatControlCenterDoubleValue(appliedRate, "U/h")
    val appliedDuration = tick.appliedDurationMin
    return if (appliedDuration != null) "$rateText / ${appliedDuration}m" else rateText
}

private fun formatHarmoniaSmbDemand(tick: HarmoniaRuntimeTickRecord): String {
    val simulated = formatControlCenterDoubleValue(tick.targetSmbU ?: 0.0, "U")
    val bounded = formatControlCenterDoubleValue(tick.boundedSmbU ?: tick.targetSmbU ?: 0.0, "U")
    val after = tick.smbDemandAfterU?.let { " RBT ${formatControlCenterDoubleValue(tick.smbDemandBeforeU ?: 0.0, "U")} -> ${formatControlCenterDoubleValue(it, "U")}" }.orEmpty()
    val cap = tick.maxSmbCapU?.let { " cap ${formatControlCenterDoubleValue(it, "U")}" }.orEmpty()
    return "$simulated -> $bounded$after$cap"
}

private fun unavailableT3cRuntimeSnapshot(): AimiT3cRuntimeSnapshot =
    AimiT3cRuntimeSnapshot(
        status = AimiT3cRuntimeStatus.Unavailable,
        owner = AimiT3cRuntimeOwner.Unavailable,
        modeText = "UNAVAILABLE",
        authorityApplied = false,
        shadowOnly = true,
        details = emptyList(),
    )

private fun unavailableHarmoniaRuntimeSnapshot(): AimiHarmoniaRuntimeSnapshot =
    AimiHarmoniaRuntimeSnapshot(
        status = AimiHarmoniaRuntimeStatus.Unavailable,
        productionModeText = "UNAVAILABLE",
        active = false,
        eligible = false,
        selectedForProduction = false,
        addsSmbAuthority = false,
        details = emptyList(),
    )

private data class AimiScoreProjection(
    val score: Float,
    val confidence: Float,
    val status: AimiProjectionStatus,
)

private fun project(scores: List<Float>): AimiScoreProjection {
    val safeScores = scores.ifEmpty { listOf(0.5f) }
    val score = safeScores.average().toFloat().coerceIn(0f, 1f)
    val meanDistance = safeScores.map { abs(it - score) }.average().toFloat()
    val confidence = (1f - meanDistance * 1.75f).coerceIn(0.40f, 1f)
    val spread = (safeScores.maxOrNull() ?: score) - (safeScores.minOrNull() ?: score)
    val status = when {
        confidence < 0.58f && spread > 0.45f -> AimiProjectionStatus.ExpertPersonalized
        confidence < 0.74f || spread > 0.24f -> AimiProjectionStatus.MixedLegacy
        else -> AimiProjectionStatus.CoherentProfile
    }
    return AimiScoreProjection(score = score, confidence = confidence, status = status)
}

private fun normalize(value: Double, key: DoublePreferenceKey): Float =
    normalize(value = value, min = key.min, max = key.max)

private fun mealBasalCapScore(value: Double): Float =
    normalize(value = value, min = 3.0, max = 10.0)

private fun normalize(value: Double, min: Double, max: Double): Float {
    if (max <= min) return 0.5f
    return ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
}

private fun inverseNormalize(value: Double, key: DoublePreferenceKey): Float =
    (1f - normalize(value = value, key = key)).coerceIn(0f, 1f)

private fun boolScore(enabled: Boolean, whenFalse: Float, whenTrue: Float): Float =
    if (enabled) whenTrue else whenFalse

private fun detail(key: DoublePreferenceKey, value: Double, unit: String?): AimiControlDetail =
    AimiControlDetail(
        titleResId = key.controlCenterTitleResId(),
        valueText = formatControlCenterDoubleValue(value = value, unit = unit),
    )

private fun detail(
    @StringRes titleResId: Int,
    value: Double,
    unit: String?,
): AimiControlDetail =
    AimiControlDetail(
        titleResId = titleResId,
        valueText = formatControlCenterDoubleValue(value = value, unit = unit),
    )

private fun boolDetail(
    key: BooleanPreferenceKey,
    enabled: Boolean,
): AimiControlDetail =
    AimiControlDetail(
        titleResId = key.controlCenterTitleResId(),
        valueResId = if (enabled) CoreUiR.string.yes else CoreUiR.string.no,
    )

private fun boolDetail(
    @StringRes titleResId: Int,
    enabled: Boolean,
): AimiControlDetail =
    AimiControlDetail(
        titleResId = titleResId,
        valueResId = if (enabled) CoreUiR.string.yes else CoreUiR.string.no,
    )

// PreferenceKey.title moved from a bare @StringRes Int (titleResId, 0/-1 meaning "unset") to
// TextRef with Milos's wave 10 migration. This Control Center screen still shows a plain resource
// id with `stringResource(Int)`, so this pulls one back out where the key still has one.
//
// The two DoubleKey special cases this used to carry - autodriveMaxBasal and meal_modes_MaxBasal -
// pointed at R.string.autodrive_max_basal_title / meal_modes_max_basal_title. Neither resource
// exists anywhere in the tree any more; that branch had already rotted while this file sat parked,
// the same way format_insulin_units had in DetermineBasalAIMI2. Both keys now carry a real title as
// TextRef.Named (KeysStrings.pref_title_autodrive_max_basal / pref_title_meal_modes_max_basal), which
// this function cannot turn into an Int - a Named string resolves through TextRef, not through a
// resource id. They fall through to the same "unlabeled" placeholder as the general Named case below.
internal fun DoublePreferenceKey.controlCenterTitleResId(): Int =
    (title as? TextRef.AndroidRes)?.id ?: R.string.aimi_control_center_unlabeled_preference

internal fun BooleanPreferenceKey.controlCenterTitleResId(): Int =
    (title as? TextRef.AndroidRes)?.id ?: R.string.aimi_control_center_unlabeled_preference

internal fun formatControlCenterDoubleValue(value: Double, unit: String?): String {
    val formatted = when {
        abs(value - value.toInt().toDouble()) < 0.005 -> value.toInt().toString()
        value >= 10.0 -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
    return if (unit.isNullOrBlank()) formatted else "$formatted $unit"
}

internal fun fiveStepIndex(score: Float): Int =
    when {
        score < 0.18f -> 0
        score < 0.36f -> 1
        score < 0.60f -> 2
        score < 0.80f -> 3
        else -> 4
    }

internal fun threeStepIndex(score: Float): Int =
    when {
        score < 0.34f -> 0
        score < 0.68f -> 1
        else -> 2
    }

@StringRes
internal fun protectionLevelLabelForIndex(index: Int): Int =
    when (index.coerceIn(0, 4)) {
        0 -> R.string.aimi_control_center_protection_level_very_protective
        1 -> R.string.aimi_control_center_protection_level_protective
        2 -> R.string.aimi_control_center_protection_level_balanced
        3 -> R.string.aimi_control_center_protection_level_corrective
        else -> R.string.aimi_control_center_protection_level_very_corrective
    }

@StringRes
internal fun protectionLevelLabel(score: Float): Int =
    protectionLevelLabelForIndex(fiveStepIndex(score))

@StringRes
internal fun mealLevelLabelForIndex(index: Int): Int =
    when (index.coerceIn(0, 4)) {
        0 -> R.string.aimi_control_center_meal_level_prudent
        1 -> R.string.aimi_control_center_meal_level_standard
        2 -> R.string.aimi_control_center_meal_level_active
        3 -> R.string.aimi_control_center_meal_level_assertive
        else -> R.string.aimi_control_center_meal_level_very_assertive
    }

@StringRes
internal fun mealLevelLabel(score: Float): Int =
    mealLevelLabelForIndex(fiveStepIndex(score))

@StringRes
internal fun stabilityLevelLabelForIndex(index: Int): Int =
    when (index.coerceIn(0, 4)) {
        0 -> R.string.aimi_control_center_stability_level_very_smooth
        1 -> R.string.aimi_control_center_stability_level_smooth
        2 -> R.string.aimi_control_center_stability_level_balanced
        3 -> R.string.aimi_control_center_stability_level_responsive
        else -> R.string.aimi_control_center_stability_level_very_responsive
    }

@StringRes
internal fun stabilityLevelLabel(score: Float): Int =
    stabilityLevelLabelForIndex(fiveStepIndex(score))

@StringRes
internal fun physioLevelLabelForIndex(index: Int): Int =
    when (index.coerceIn(0, 2)) {
        0 -> R.string.aimi_control_center_physio_level_low
        1 -> R.string.aimi_control_center_physio_level_moderate
        else -> R.string.aimi_control_center_physio_level_strong
    }

@StringRes
internal fun physioLevelLabel(score: Float): Int =
    physioLevelLabelForIndex(threeStepIndex(score))
