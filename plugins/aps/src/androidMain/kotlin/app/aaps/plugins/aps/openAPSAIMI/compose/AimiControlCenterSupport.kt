package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.annotation.StringRes
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import kotlin.math.abs

// AimiAutonomyMode itself moved to commonMain - the decision path needs the type, not the label.
// Its display string is UI-only, so it is a plain extension function here instead of a constructor
// property, and the four resource ids are unchanged.
@StringRes
internal fun AimiAutonomyMode.labelResId(): Int = when (this) {
    AimiAutonomyMode.Observation -> R.string.aimi_control_center_autonomy_observation
    AimiAutonomyMode.Recommendations -> R.string.aimi_control_center_autonomy_recommendations
    AimiAutonomyMode.AssistedApplication -> R.string.aimi_control_center_autonomy_assisted
    AimiAutonomyMode.ControlledAuthority -> R.string.aimi_control_center_autonomy_controlled
}

internal data class AimiControlCenterDraft(
    val protectionLevel: Int,
    val mealCaptureLevel: Int,
    val stabilityLevel: Int,
    val physioLevel: Int,
    val autonomyMode: AimiAutonomyMode,
)

internal data class AimiControlCenterPendingChanges(
    val familyPlans: List<AimiFamilyWritebackPlan>,
) {
    val changedFamilyCount: Int get() = familyPlans.size
    val changedSettingsCount: Int get() = familyPlans.sumOf { it.changes.size }
    val hasChanges: Boolean get() = familyPlans.isNotEmpty()

    fun familyPlan(id: AimiBehaviorFamilyId): AimiFamilyWritebackPlan? =
        familyPlans.firstOrNull { it.familyId == id }
}

internal data class AimiFamilyWritebackPlan(
    val familyId: AimiBehaviorFamilyId,
    @StringRes val currentLabelResId: Int,
    @StringRes val targetLabelResId: Int,
    @StringRes val noteResId: Int? = null,
    val changes: List<AimiPreferenceChange>,
)

internal data class AimiPreferenceChange(
    val preferenceKey: String,
    @StringRes val titleResId: Int,
    val before: AimiValueDescriptor,
    val after: AimiValueDescriptor,
    val apply: (Preferences) -> Unit,
)

internal data class AimiValueDescriptor(
    val valueText: String? = null,
    @StringRes val valueResId: Int? = null,
)

internal fun readAimiControlCenterDraft(preferences: Preferences): AimiControlCenterDraft {
    val snapshot = buildAimiControlCenterSnapshot(preferences)
    return AimiControlCenterDraft(
        protectionLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.Protection).normalizedScore),
        mealCaptureLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.MealCapture).normalizedScore),
        stabilityLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.Stability).normalizedScore),
        physioLevel = threeStepIndex(snapshot.family(AimiBehaviorFamilyId.Physio).normalizedScore),
        autonomyMode = readAutonomyMode(preferences),
    )
}

internal fun buildAimiControlCenterPendingChanges(
    preferences: Preferences,
    currentDraft: AimiControlCenterDraft,
    targetDraft: AimiControlCenterDraft,
): AimiControlCenterPendingChanges {
    val plans = mutableListOf<AimiFamilyWritebackPlan>()
    if (currentDraft.protectionLevel != targetDraft.protectionLevel) {
        val plan = buildProtectionPlan(preferences, currentDraft.protectionLevel, targetDraft.protectionLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.mealCaptureLevel != targetDraft.mealCaptureLevel) {
        val plan = buildMealCapturePlan(preferences, currentDraft.mealCaptureLevel, targetDraft.mealCaptureLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.stabilityLevel != targetDraft.stabilityLevel) {
        val plan = buildStabilityPlan(preferences, currentDraft.stabilityLevel, targetDraft.stabilityLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.physioLevel != targetDraft.physioLevel) {
        val plan = buildPhysioPlan(preferences, currentDraft.physioLevel, targetDraft.physioLevel)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    if (currentDraft.autonomyMode != targetDraft.autonomyMode) {
        val plan = buildAutonomyPlan(preferences, currentDraft.autonomyMode, targetDraft.autonomyMode)
        if (plan.changes.isNotEmpty()) plans.add(plan)
    }
    return AimiControlCenterPendingChanges(familyPlans = plans)
}

internal fun applyAimiControlCenterPendingChanges(
    preferences: Preferences,
    pendingChanges: AimiControlCenterPendingChanges,
) {
    pendingChanges.familyPlans
        .flatMap { it.changes }
        .forEach { it.apply(preferences) }
}

internal fun projectionStatusSummaryResId(status: AimiProjectionStatus): Int =
    when (status) {
        AimiProjectionStatus.CoherentProfile -> R.string.aimi_control_center_status_coherent_summary
        AimiProjectionStatus.MixedLegacy -> R.string.aimi_control_center_status_mixed_summary
        AimiProjectionStatus.ExpertPersonalized -> R.string.aimi_control_center_status_expert_summary
    }

internal fun AimiControlCenterSnapshot.family(id: AimiBehaviorFamilyId): AimiBehaviorFamilySnapshot =
    families.first { it.id == id }

private fun readAutonomyMode(preferences: Preferences): AimiAutonomyMode {
    // Classic (V1/V2) autodrive removed — autonomy is a gradation of V3 production authority (no shadow tier).
    val autoDriveActive = preferences.get(BooleanKey.OApsAIMIautoDriveActive)
    val hyperTrajectory = autoDriveActive && preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease)
    val authoritative = autoDriveActive && preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative)
    val recursiveAuthority = autoDriveActive && preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
    return when {
        !autoDriveActive -> AimiAutonomyMode.Observation
        recursiveAuthority || authoritative -> AimiAutonomyMode.ControlledAuthority
        hyperTrajectory -> AimiAutonomyMode.AssistedApplication
        else -> AimiAutonomyMode.Recommendations
    }
}

private fun buildProtectionPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel.coerceIn(0, 4)) {
        0 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMIMaxSMB, listOf(0.80, 1.00, 1.30, 1.80, 2.40), currentLevel, targetLevel, R.string.openapsaimi_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, listOf(1.00, 1.25, 1.60, 2.20, 3.00), currentLevel, targetLevel, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, listOf(1.05, 1.10, 1.20, 1.35, 1.50), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, listOf(0.50, 1.00, 2.00, 3.00, 4.00), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        1 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMIMaxSMB, listOf(0.80, 1.00, 1.30, 1.80, 2.40), currentLevel, targetLevel, R.string.openapsaimi_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, listOf(1.00, 1.25, 1.60, 2.20, 3.00), currentLevel, targetLevel, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, listOf(1.05, 1.10, 1.20, 1.35, 1.50), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, listOf(0.50, 1.00, 2.00, 3.00, 4.00), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        2 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMIMaxSMB, listOf(0.80, 1.00, 1.30, 1.80, 2.40), currentLevel, targetLevel, R.string.openapsaimi_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, listOf(1.00, 1.25, 1.60, 2.20, 3.00), currentLevel, targetLevel, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, listOf(1.05, 1.10, 1.20, 1.35, 1.50), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, listOf(0.50, 1.00, 2.00, 3.00, 4.00), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        3 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMIMaxSMB, listOf(0.80, 1.00, 1.30, 1.80, 2.40), currentLevel, targetLevel, R.string.openapsaimi_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, listOf(1.00, 1.25, 1.60, 2.20, 3.00), currentLevel, targetLevel, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, listOf(1.05, 1.10, 1.20, 1.35, 1.50), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, listOf(0.50, 1.00, 2.00, 3.00, 4.00), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
        else -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMIMaxSMB, listOf(0.80, 1.00, 1.30, 1.80, 2.40), currentLevel, targetLevel, R.string.openapsaimi_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHighBGMaxSMB, listOf(1.00, 1.25, 1.60, 2.20, 3.00), currentLevel, targetLevel, R.string.openapsaimi_highBG_maxsmb_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, listOf(1.05, 1.10, 1.20, 1.35, 1.50), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_factor_title, "x"),
            ladderChange(preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, listOf(0.50, 1.00, 2.00, 3.00, 4.00), currentLevel, targetLevel, R.string.oaps_aimi_priority_max_iob_extra_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_pkpd_relief_factor_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_redcarpet_restore_title, null),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Protection,
        currentLabelResId = protectionLevelLabelForIndex(currentLevel),
        targetLabelResId = protectionLevelLabelForIndex(targetLevel),
        changes = changes,
    )
}

private fun buildMealCapturePlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel.coerceIn(0, 4)) {
        0 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, false),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, false),
            ladderChange(preferences, DoubleKey.autodriveMaxBasal, listOf(3.0, 4.5, 6.0, 7.5, 9.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.meal_modes_MaxBasal, listOf(4.0, 5.5, 7.0, 8.5, 10.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, listOf(0.045, 0.060, 0.075, 0.090, 0.105), currentLevel, targetLevel, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, listOf(0.50, 0.80, 1.20, 1.80, 2.80), currentLevel, targetLevel, R.string.prebolus_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, listOf(0.05, 0.10, 0.20, 0.35, 0.60), currentLevel, targetLevel, R.string.prebolussmall_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, listOf(22.0, 18.0, 15.0, 12.0, 10.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, listOf(38.0, 32.0, 28.0, 24.0, 20.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
        )
        1 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, false),
            ladderChange(preferences, DoubleKey.autodriveMaxBasal, listOf(3.0, 4.5, 6.0, 7.5, 9.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.meal_modes_MaxBasal, listOf(4.0, 5.5, 7.0, 8.5, 10.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, listOf(0.045, 0.060, 0.075, 0.090, 0.105), currentLevel, targetLevel, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, listOf(0.50, 0.80, 1.20, 1.80, 2.80), currentLevel, targetLevel, R.string.prebolus_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, listOf(0.05, 0.10, 0.20, 0.35, 0.60), currentLevel, targetLevel, R.string.prebolussmall_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, listOf(22.0, 18.0, 15.0, 12.0, 10.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, listOf(38.0, 32.0, 28.0, 24.0, 20.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
        )
        2 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, false),
            ladderChange(preferences, DoubleKey.autodriveMaxBasal, listOf(3.0, 4.5, 6.0, 7.5, 9.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.meal_modes_MaxBasal, listOf(4.0, 5.5, 7.0, 8.5, 10.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, listOf(0.045, 0.060, 0.075, 0.090, 0.105), currentLevel, targetLevel, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, listOf(0.50, 0.80, 1.20, 1.80, 2.80), currentLevel, targetLevel, R.string.prebolus_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, listOf(0.05, 0.10, 0.20, 0.35, 0.60), currentLevel, targetLevel, R.string.prebolussmall_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, listOf(22.0, 18.0, 15.0, 12.0, 10.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, listOf(38.0, 32.0, 28.0, 24.0, 20.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
        )
        3 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, true),
            ladderChange(preferences, DoubleKey.autodriveMaxBasal, listOf(3.0, 4.5, 6.0, 7.5, 9.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.meal_modes_MaxBasal, listOf(4.0, 5.5, 7.0, 8.5, 10.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, listOf(0.045, 0.060, 0.075, 0.090, 0.105), currentLevel, targetLevel, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, listOf(0.50, 0.80, 1.20, 1.80, 2.80), currentLevel, targetLevel, R.string.prebolus_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, listOf(0.05, 0.10, 0.20, 0.35, 0.60), currentLevel, targetLevel, R.string.prebolussmall_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, listOf(22.0, 18.0, 15.0, 12.0, 10.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, listOf(38.0, 32.0, 28.0, 24.0, 20.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
        )
        else -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, true),
            ladderChange(preferences, DoubleKey.autodriveMaxBasal, listOf(3.0, 4.5, 6.0, 7.5, 9.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.meal_modes_MaxBasal, listOf(4.0, 5.5, 7.0, 8.5, 10.0), currentLevel, targetLevel, unit = "U/h"),
            ladderChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, listOf(0.045, 0.060, 0.075, 0.090, 0.105), currentLevel, targetLevel, R.string.aimi_mpc_u_per_kg_title, "U/kg/5m"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, listOf(0.50, 0.80, 1.20, 1.80, 2.80), currentLevel, targetLevel, R.string.prebolus_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, listOf(0.05, 0.10, 0.20, 0.35, 0.60), currentLevel, targetLevel, R.string.prebolussmall_autodrive_mode_title, "U"),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, listOf(22.0, 18.0, 15.0, 12.0, 10.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
            ladderChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, listOf(38.0, 32.0, 28.0, 24.0, 20.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.MealCapture,
        currentLabelResId = mealLevelLabelForIndex(currentLevel),
        targetLabelResId = mealLevelLabelForIndex(targetLevel),
        noteResId = R.string.aimi_control_center_meal_apply_note,
        changes = changes,
    )
}

private fun buildStabilityPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    // Tail floor ladder is shared: left/smoother = stronger damping (lower floor inside PKPD band).
    val tailFloorLadder = PkpdSmbTailDamping.STABILITY_FAMILY_FLOOR_LADDER
    val changes = when (targetLevel.coerceIn(0, 4)) {
        0 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMISmbTailDamping, tailFloorLadder, currentLevel, targetLevel, R.string.oaps_aimi_smb_tail_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, listOf(0.30, 0.45, 0.60, 0.72, 0.85), currentLevel, targetLevel, R.string.oaps_aimi_smb_exercise_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, listOf(0.40, 0.55, 0.70, 0.80, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, false),
            ladderChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, listOf(0.02, 0.04, 0.06, 0.08, 0.10), currentLevel, targetLevel, unit = null),
        )
        1 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMISmbTailDamping, tailFloorLadder, currentLevel, targetLevel, R.string.oaps_aimi_smb_tail_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, listOf(0.30, 0.45, 0.60, 0.72, 0.85), currentLevel, targetLevel, R.string.oaps_aimi_smb_exercise_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, listOf(0.40, 0.55, 0.70, 0.80, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, false),
            ladderChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, listOf(0.02, 0.04, 0.06, 0.08, 0.10), currentLevel, targetLevel, unit = null),
        )
        2 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMISmbTailDamping, tailFloorLadder, currentLevel, targetLevel, R.string.oaps_aimi_smb_tail_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, listOf(0.30, 0.45, 0.60, 0.72, 0.85), currentLevel, targetLevel, R.string.oaps_aimi_smb_exercise_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, listOf(0.40, 0.55, 0.70, 0.80, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, false),
            ladderChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, listOf(0.02, 0.04, 0.06, 0.08, 0.10), currentLevel, targetLevel, unit = null),
        )
        3 -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMISmbTailDamping, tailFloorLadder, currentLevel, targetLevel, R.string.oaps_aimi_smb_tail_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, listOf(0.30, 0.45, 0.60, 0.72, 0.85), currentLevel, targetLevel, R.string.oaps_aimi_smb_exercise_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, listOf(0.40, 0.55, 0.70, 0.80, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, true),
            ladderChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, listOf(0.02, 0.04, 0.06, 0.08, 0.10), currentLevel, targetLevel, unit = null),
        )
        else -> listOfNotNull(
            ladderChange(preferences, DoubleKey.OApsAIMISmbTailDamping, tailFloorLadder, currentLevel, targetLevel, R.string.oaps_aimi_smb_tail_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbExerciseDamping, listOf(0.30, 0.45, 0.60, 0.72, 0.85), currentLevel, targetLevel, R.string.oaps_aimi_smb_exercise_damping_title, null),
            ladderChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, listOf(0.40, 0.55, 0.70, 0.80, 0.90), currentLevel, targetLevel, R.string.oaps_aimi_smb_late_fat_damping_title, null),
            booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, R.string.oaps_aimi_adaptive_basal_title),
            booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, true),
            ladderChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, listOf(0.02, 0.04, 0.06, 0.08, 0.10), currentLevel, targetLevel, unit = null),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Stability,
        currentLabelResId = stabilityLevelLabelForIndex(currentLevel),
        targetLabelResId = stabilityLevelLabelForIndex(targetLevel),
        changes = changes,
    )
}

private fun buildPhysioPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel.coerceIn(0, 2)) {
        0 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, false, R.string.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, false, R.string.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, false, R.string.aimi_physio_hrv_enable_title),
        )
        1 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, true, R.string.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, true, R.string.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, false, R.string.aimi_physio_hrv_enable_title),
        )
        else -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, true, R.string.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, true, R.string.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, true, R.string.aimi_physio_hrv_enable_title),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Physio,
        currentLabelResId = physioLevelLabelForIndex(currentLevel),
        targetLabelResId = physioLevelLabelForIndex(targetLevel),
        noteResId = R.string.aimi_control_center_physio_apply_note,
        changes = changes,
    )
}

private fun buildAutonomyPlan(
    preferences: Preferences,
    currentLevel: AimiAutonomyMode,
    targetLevel: AimiAutonomyMode,
): AimiFamilyWritebackPlan {
    val changes = when (targetLevel) {
        AimiAutonomyMode.Observation -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, false, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, false),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.Recommendations -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, false),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.AssistedApplication -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.ControlledAuthority -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, R.string.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, true),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, true),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Autonomy,
        currentLabelResId = currentLevel.labelResId(),
        targetLabelResId = targetLevel.labelResId(),
        noteResId = R.string.aimi_control_center_autonomy_apply_note,
        changes = changes,
    )
}

private fun booleanChange(
    preferences: Preferences,
    key: BooleanPreferenceKey,
    targetValue: Boolean,
    @StringRes titleResId: Int = key.controlCenterTitleResId(),
): AimiPreferenceChange? {
    val currentValue = preferences.get(key)
    if (currentValue == targetValue) return null
    return AimiPreferenceChange(
        preferenceKey = key.key,
        titleResId = titleResId,
        before = AimiValueDescriptor(valueResId = if (currentValue) CoreUiR.string.yes else CoreUiR.string.no),
        after = AimiValueDescriptor(valueResId = if (targetValue) CoreUiR.string.yes else CoreUiR.string.no),
        apply = { prefs -> prefs.put(key, targetValue) },
    )
}

private fun ladderChange(
    preferences: Preferences,
    key: DoublePreferenceKey,
    ladder: List<Double>,
    currentLevel: Int,
    targetLevel: Int,
    @StringRes titleResId: Int = key.controlCenterTitleResId(),
    unit: String?,
    increasingSliderLevelRaisesValue: Boolean = true,
): AimiPreferenceChange? {
    if (ladder.isEmpty()) return null
    val delta = targetLevel - currentLevel
    if (delta == 0) return null
    val currentValue = preferences.get(key)
    val currentIndex = ladder.indices.minByOrNull { index -> abs(ladder[index] - currentValue) } ?: 0
    val targetIndex = (currentIndex + delta).coerceIn(0, ladder.lastIndex)
    val valueShouldIncrease = if (increasingSliderLevelRaisesValue) delta > 0 else delta < 0
    val targetValue = resolveLadderTargetValue(
        ladder = ladder,
        key = key,
        currentValue = currentValue,
        ladderTargetValue = ladder[targetIndex],
        valueShouldIncrease = valueShouldIncrease,
    )
    return doubleChange(preferences, key, targetValue, titleResId, unit)
}

private fun resolveLadderTargetValue(
    ladder: List<Double>,
    key: DoublePreferenceKey,
    currentValue: Double,
    ladderTargetValue: Double,
    valueShouldIncrease: Boolean,
): Double {
    val epsilon = 0.0001
    val wouldMoveWrongWay = if (valueShouldIncrease) {
        ladderTargetValue <= currentValue + epsilon
    } else {
        ladderTargetValue >= currentValue - epsilon
    }
    if (!wouldMoveWrongWay) return ladderTargetValue
    return extrapolateBeyondLadder(ladder, key, currentValue, upward = valueShouldIncrease)
}

private fun extrapolateBeyondLadder(
    ladder: List<Double>,
    key: DoublePreferenceKey,
    currentValue: Double,
    upward: Boolean,
): Double {
    val step = if (ladder.size >= 2) {
        abs(ladder[ladder.lastIndex] - ladder[ladder.lastIndex - 1])
    } else {
        abs(ladder.last() - ladder.first()).coerceAtLeast(0.01)
    }
    return if (upward) {
        (currentValue + step).coerceIn(key.min, key.max)
    } else {
        (currentValue - step).coerceIn(key.min, key.max)
    }
}

private fun doubleChange(
    preferences: Preferences,
    key: DoublePreferenceKey,
    targetValue: Double,
    @StringRes titleResId: Int,
    unit: String?,
): AimiPreferenceChange? {
    val clampedTarget = targetValue.coerceIn(key.min, key.max)
    val currentValue = preferences.get(key)
    if (abs(currentValue - clampedTarget) < 0.0001) return null
    return AimiPreferenceChange(
        preferenceKey = key.key,
        titleResId = titleResId,
        before = AimiValueDescriptor(valueText = formatControlCenterDoubleValue(currentValue, unit)),
        after = AimiValueDescriptor(valueText = formatControlCenterDoubleValue(clampedTarget, unit)),
        apply = { prefs -> prefs.put(key, clampedTarget) },
    )
}
