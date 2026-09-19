package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
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
    val titleResId: TextRef,
    val questionResId: TextRef,
    val leftAnchorResId: TextRef,
    val rightAnchorResId: TextRef,
    val levelLabelResId: TextRef,
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
    val titleResId: TextRef,
    val summaryResId: TextRef,
    val details: List<AimiControlDetail>,
)

internal data class AimiControlDetail(
    val titleResId: TextRef,
    val valueText: String? = null,
    val valueResId: TextRef? = null,
)

internal enum class AimiProjectionStatus(val labelResId: TextRef) {
    CoherentProfile(ApsStrings.aimi_control_center_coherent_profile),
    MixedLegacy(ApsStrings.aimi_control_center_mixed_legacy),
    ExpertPersonalized(ApsStrings.aimi_control_center_expert_personalized),
}

internal enum class AimiT3cRuntimeStatus(val labelResId: TextRef) {
    NativeApplied(ApsStrings.aimi_control_center_t3c_status_native_applied),
    NativeReady(ApsStrings.aimi_control_center_t3c_status_native_ready),
    NativeBlocked(ApsStrings.aimi_control_center_t3c_status_native_blocked),
    LegacyFallback(ApsStrings.aimi_control_center_t3c_status_legacy_fallback),
    SafetyTerminal(ApsStrings.aimi_control_center_t3c_status_safety_terminal),
    Unavailable(ApsStrings.aimi_control_center_t3c_status_unavailable),
}

internal enum class AimiT3cRuntimeOwner(val labelResId: TextRef) {
    NativeRbt(ApsStrings.aimi_control_center_t3c_owner_native),
    LegacyBypass(ApsStrings.aimi_control_center_t3c_owner_legacy),
    SafetyGate(ApsStrings.aimi_control_center_t3c_owner_safety),
    Unavailable(ApsStrings.aimi_control_center_t3c_owner_unavailable),
}

internal data class AimiT3cRuntimeSnapshot(
    val status: AimiT3cRuntimeStatus,
    val owner: AimiT3cRuntimeOwner,
    val modeText: String,
    val authorityApplied: Boolean,
    val shadowOnly: Boolean,
    val details: List<AimiControlDetail>,
)

internal enum class AimiHarmoniaRuntimeStatus(val labelResId: TextRef) {
    NativeApplied(ApsStrings.aimi_control_center_harmonia_status_native_applied),
    NativeReady(ApsStrings.aimi_control_center_harmonia_status_native_ready),
    NativeBlocked(ApsStrings.aimi_control_center_harmonia_status_native_blocked),
    T3cPriority(ApsStrings.aimi_control_center_harmonia_status_t3c_priority),
    Unavailable(ApsStrings.aimi_control_center_harmonia_status_unavailable),
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
        titleResId = ApsStrings.aimi_control_center_protection_title,
        questionResId = ApsStrings.aimi_control_center_protection_question,
        leftAnchorResId = ApsStrings.aimi_control_center_protection_left,
        rightAnchorResId = ApsStrings.aimi_control_center_protection_right,
        levelLabelResId = protectionLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Protection),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Protection),
        status = projection.status,
        details = listOf(
            detail(ApsStrings.openapsaimi_maxsmb_title, preferences.get(DoubleKey.OApsAIMIMaxSMB), "U"),
            detail(ApsStrings.openapsaimi_highBG_maxsmb_title, preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB), "U"),
            detail(ApsStrings.oaps_aimi_priority_max_iob_factor_title, preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor), "x"),
            detail(ApsStrings.oaps_aimi_priority_max_iob_extra_title, preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU), "U"),
            detail(ApsStrings.oaps_aimi_pkpd_relief_factor_title, preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor), null),
            detail(ApsStrings.oaps_aimi_redcarpet_restore_title, preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold), null),
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
        titleResId = ApsStrings.aimi_control_center_meal_title,
        questionResId = ApsStrings.aimi_control_center_meal_question,
        leftAnchorResId = ApsStrings.aimi_control_center_meal_left,
        rightAnchorResId = ApsStrings.aimi_control_center_meal_right,
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
            detail(ApsStrings.aimi_mpc_u_per_kg_title, preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep), "U/kg/5m"),
            detail(ApsStrings.prebolus_autodrive_mode_title, preferences.get(DoubleKey.OApsAIMIautodrivePrebolus), "U"),
            detail(ApsStrings.prebolussmall_autodrive_mode_title, preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus), "U"),
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
        titleResId = ApsStrings.aimi_control_center_stability_title,
        questionResId = ApsStrings.aimi_control_center_stability_question,
        leftAnchorResId = ApsStrings.aimi_control_center_stability_left,
        rightAnchorResId = ApsStrings.aimi_control_center_stability_right,
        levelLabelResId = stabilityLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Stability),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Stability),
        status = projection.status,
        details = listOf(
            // Show the value the loop actually uses (legacy ≤0.55 → neutral), so the row matches the slider.
            detail(
                ApsStrings.oaps_aimi_smb_tail_damping_title,
                PkpdSmbTailDamping.effectiveStoredValue(preferences.get(DoubleKey.OApsAIMISmbTailDamping)),
                null,
            ),
            detail(ApsStrings.oaps_aimi_smb_exercise_damping_title, preferences.get(DoubleKey.OApsAIMISmbExerciseDamping), null),
            detail(ApsStrings.oaps_aimi_smb_late_fat_damping_title, preferences.get(DoubleKey.OApsAIMISmbLateFatDamping), null),
            boolDetail(ApsStrings.oaps_aimi_adaptive_basal_title, adaptiveBasalEnabled),
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
        titleResId = ApsStrings.aimi_control_center_physio_title,
        questionResId = ApsStrings.aimi_control_center_physio_question,
        leftAnchorResId = ApsStrings.aimi_control_center_physio_left,
        rightAnchorResId = ApsStrings.aimi_control_center_physio_right,
        levelLabelResId = physioLevelLabel(projection.score),
        normalizedScore = projection.score,
        confidence = projection.confidence,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Physio),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Physio),
        status = projection.status,
        details = listOf(
            boolDetail(ApsStrings.aimi_physio_enable_title, assistantEnabled),
            boolDetail(ApsStrings.aimi_physio_sleep_enable_title, sleepEnabled),
            boolDetail(ApsStrings.aimi_physio_hrv_enable_title, hrvEnabled),
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
        !autoDriveActive -> ApsStrings.aimi_control_center_autonomy_observation
        recursiveAuthority || authoritative -> ApsStrings.aimi_control_center_autonomy_controlled
        hyperTrajectory -> ApsStrings.aimi_control_center_autonomy_assisted
        else -> ApsStrings.aimi_control_center_autonomy_recommendations
    }
    val score = when (levelLabelResId) {
        ApsStrings.aimi_control_center_autonomy_observation -> 0.12f
        ApsStrings.aimi_control_center_autonomy_recommendations -> 0.42f
        ApsStrings.aimi_control_center_autonomy_assisted -> 0.72f
        else -> 0.95f
    }

    return AimiBehaviorFamilySnapshot(
        id = AimiBehaviorFamilyId.Autonomy,
        titleResId = ApsStrings.aimi_control_center_autonomy_title,
        questionResId = ApsStrings.aimi_control_center_autonomy_question,
        leftAnchorResId = ApsStrings.aimi_control_center_autonomy_left,
        rightAnchorResId = ApsStrings.aimi_control_center_autonomy_right,
        levelLabelResId = levelLabelResId,
        normalizedScore = score,
        confidence = 1.0f,
        managedPreferenceCount = AimiBehaviorFamilyRegistry.managedCount(AimiBehaviorFamilyId.Autonomy),
        expertPreferenceCount = AimiBehaviorFamilyRegistry.expertCount(AimiBehaviorFamilyId.Autonomy),
        status = AimiProjectionStatus.CoherentProfile,
        // HTR is surfaced in the Meal-capture family; avoid a duplicate detail-row title here.
        details = listOf(
            boolDetail(ApsStrings.oaps_aimi_enableMlautoDriveActive_title, autoDriveActive),
            boolDetail(BooleanKey.OApsAIMIRecursiveBeliefAuthority, recursiveAuthority),
            boolDetail(BooleanKey.OApsAIMIautoDriveAuthoritative, authoritative),
            boolDetail(BooleanKey.OApsAIMIautodriveAggressiveSmbFloor, aggressiveSmbFloor),
        ),
    )
}

private fun buildContextSection(preferences: Preferences): AimiControlSectionSnapshot =
    AimiControlSectionSnapshot(
        titleResId = ApsStrings.aimi_control_center_context_title,
        summaryResId = ApsStrings.aimi_control_center_context_summary,
        details = listOf(
            detail(ApsStrings.oaps_aimi_weight_title, preferences.get(DoubleKey.OApsAIMIweight), "kg"),
            detail(ApsStrings.oaps_aimi_cho_title, preferences.get(DoubleKey.OApsAIMICHO), "g"),
            detail(ApsStrings.oaps_aimi_tdd7_title, preferences.get(DoubleKey.OApsAIMITDD7), "U"),
            AimiControlDetail(
                titleResId = ApsStrings.OApsAIMI_Enable_pregnancy,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIpregnancy)) CoreUiStrings.yes else CoreUiStrings.no,
            ),
            AimiControlDetail(
                titleResId = ApsStrings.OApsAIMI_Enable_honeymoon,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIhoneymoon)) CoreUiStrings.yes else CoreUiStrings.no,
            ),
            AimiControlDetail(
                titleResId = ApsStrings.aimi_control_center_cycle_module_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIwcycle)) CoreUiStrings.yes else CoreUiStrings.no,
            ),
            AimiControlDetail(
                titleResId = ApsStrings.oaps_aimi_thyroid_enabled_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMIThyroidEnabled)) CoreUiStrings.yes else CoreUiStrings.no,
            ),
            AimiControlDetail(
                titleResId = ApsStrings.endo_enable_title,
                valueResId = if (preferences.get(BooleanKey.AimiEndometriosisEnable)) CoreUiStrings.yes else CoreUiStrings.no,
            ),
            AimiControlDetail(
                titleResId = ApsStrings.oaps_aimi_ngr_enabled_title,
                valueResId = if (preferences.get(BooleanKey.OApsAIMINightGrowthEnabled)) CoreUiStrings.yes else CoreUiStrings.no,
            ),
        ),
    )

private fun buildSourceSection(preferences: Preferences): AimiControlSectionSnapshot {
    val sourceMode = preferences.get(AimiStringKey.ActivitySourceMode)
    // AimiStringKey.ActivitySourceMode.entries became Map<String, TextRef> with the wave 10
    // migration - TextRef.Named values, not resource ids, so they cannot answer this Int-typed
    // field. Same four options, same four resources, read directly instead of through the key.
    val sourceResId = when (sourceMode) {
        "prefer_wear" -> ApsStrings.pref_aimi_steps_source_wear
        "auto" -> ApsStrings.pref_aimi_steps_source_auto
        "hc_only" -> ApsStrings.pref_aimi_steps_source_hc
        else -> ApsStrings.pref_aimi_steps_source_disabled
    }
    val ouraConfigured = preferences.get(AimiStringKey.OuraPersonalAccessToken).isNotBlank()
    return AimiControlSectionSnapshot(
        titleResId = ApsStrings.aimi_control_center_sources_title,
        summaryResId = ApsStrings.aimi_control_center_sources_summary,
        details = listOf(
            AimiControlDetail(
                titleResId = ApsStrings.pref_aimi_steps_source_title,
                valueText = sourceMode,
                valueResId = sourceResId,
            ),
            AimiControlDetail(
                titleResId = ApsStrings.aimi_oura_pat_title,
                valueResId = if (ouraConfigured) ApsStrings.aimi_control_center_configured else ApsStrings.aimi_control_center_not_configured,
            ),
        ),
    )
}

// internal, not private: the androidMain AimiControlCenterRuntimeLoaders.kt calls these as its
// no-history fallback, and Kotlin's file-private visibility does not cross files even within the
// same module.
internal fun unavailableT3cRuntimeSnapshot(): AimiT3cRuntimeSnapshot =
    AimiT3cRuntimeSnapshot(
        status = AimiT3cRuntimeStatus.Unavailable,
        owner = AimiT3cRuntimeOwner.Unavailable,
        modeText = "UNAVAILABLE",
        authorityApplied = false,
        shadowOnly = true,
        details = emptyList(),
    )

internal fun unavailableHarmoniaRuntimeSnapshot(): AimiHarmoniaRuntimeSnapshot =
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
    titleResId: TextRef,
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
        valueResId = if (enabled) CoreUiStrings.yes else CoreUiStrings.no,
    )

private fun boolDetail(
    titleResId: TextRef,
    enabled: Boolean,
): AimiControlDetail =
    AimiControlDetail(
        titleResId = titleResId,
        valueResId = if (enabled) CoreUiStrings.yes else CoreUiStrings.no,
    )

// PreferenceKey.title moved from a bare @StringRes Int (titleResId, 0/-1 meaning "unset") to
// TextRef with Milos's wave 10 migration, and this function now returns that same TextRef type
// directly instead of pulling an Int back out of it - see AimiControlCenterScreen.kt, which reads
// this through `stringResource(TextRef)`.
//
// The two DoubleKey special cases this used to carry - autodriveMaxBasal and meal_modes_MaxBasal -
// pointed at R.string.autodrive_max_basal_title / meal_modes_max_basal_title. Neither resource
// exists anywhere in the tree any more; that branch had already rotted while this file sat parked,
// the same way format_insulin_units had in DetermineBasalAIMI2. Both keys now carry a real title as
// TextRef.Named (KeysStrings.pref_title_autodrive_max_basal / pref_title_meal_modes_max_basal), and
// since those are already a TextRef, they fall through to the same "unlabeled" placeholder as the
// general Named case below only because they are not the TextRef.AndroidRes variant this function
// still special-cases - not because a Named value cannot be represented any more.
internal fun DoublePreferenceKey.controlCenterTitleResId(): TextRef =
    (title as? TextRef.AndroidRes) ?: ApsStrings.aimi_control_center_unlabeled_preference

internal fun BooleanPreferenceKey.controlCenterTitleResId(): TextRef =
    (title as? TextRef.AndroidRes) ?: ApsStrings.aimi_control_center_unlabeled_preference

internal fun formatControlCenterDoubleValue(value: Double, unit: String?): String {
    val formatted = when {
        abs(value - value.toInt().toDouble()) < 0.005 -> value.toInt().toString()
        value >= 10.0 -> aimiFmt1(value)
        else -> aimiFmt2(value)
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

internal fun protectionLevelLabelForIndex(index: Int): TextRef =
    when (index.coerceIn(0, 4)) {
        0 -> ApsStrings.aimi_control_center_protection_level_very_protective
        1 -> ApsStrings.aimi_control_center_protection_level_protective
        2 -> ApsStrings.aimi_control_center_protection_level_balanced
        3 -> ApsStrings.aimi_control_center_protection_level_corrective
        else -> ApsStrings.aimi_control_center_protection_level_very_corrective
    }

internal fun protectionLevelLabel(score: Float): TextRef =
    protectionLevelLabelForIndex(fiveStepIndex(score))

internal fun mealLevelLabelForIndex(index: Int): TextRef =
    when (index.coerceIn(0, 4)) {
        0 -> ApsStrings.aimi_control_center_meal_level_prudent
        1 -> ApsStrings.aimi_control_center_meal_level_standard
        2 -> ApsStrings.aimi_control_center_meal_level_active
        3 -> ApsStrings.aimi_control_center_meal_level_assertive
        else -> ApsStrings.aimi_control_center_meal_level_very_assertive
    }

internal fun mealLevelLabel(score: Float): TextRef =
    mealLevelLabelForIndex(fiveStepIndex(score))

internal fun stabilityLevelLabelForIndex(index: Int): TextRef =
    when (index.coerceIn(0, 4)) {
        0 -> ApsStrings.aimi_control_center_stability_level_very_smooth
        1 -> ApsStrings.aimi_control_center_stability_level_smooth
        2 -> ApsStrings.aimi_control_center_stability_level_balanced
        3 -> ApsStrings.aimi_control_center_stability_level_responsive
        else -> ApsStrings.aimi_control_center_stability_level_very_responsive
    }

internal fun stabilityLevelLabel(score: Float): TextRef =
    stabilityLevelLabelForIndex(fiveStepIndex(score))

internal fun physioLevelLabelForIndex(index: Int): TextRef =
    when (index.coerceIn(0, 2)) {
        0 -> ApsStrings.aimi_control_center_physio_level_low
        1 -> ApsStrings.aimi_control_center_physio_level_moderate
        else -> ApsStrings.aimi_control_center_physio_level_strong
    }

internal fun physioLevelLabel(score: Float): TextRef =
    physioLevelLabelForIndex(threeStepIndex(score))
