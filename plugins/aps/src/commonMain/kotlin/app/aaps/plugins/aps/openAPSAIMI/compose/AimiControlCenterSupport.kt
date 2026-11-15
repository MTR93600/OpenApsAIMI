package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDamping
import kotlin.math.abs

// AimiAutonomyMode.labelResId() used to live here as a plain androidMain extension function
// returning a bare @StringRes Int. D4 replaced it with the commonMain
// AimiAutonomyMode.controlCenterLabel(): TextRef next to authorityRank() in
// AimiBehaviorRuntimeProfile.kt, using the same four ApsStrings.aimi_control_center_autonomy_*
// entries - see that file. buildAutonomyPlan() below calls the new extension.

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
    val currentLabelResId: TextRef,
    val targetLabelResId: TextRef,
    val noteResId: TextRef? = null,
    val changes: List<AimiPreferenceChange>,
)

internal data class AimiPreferenceChange(
    val preferenceKey: String,
    val titleResId: TextRef,
    val before: AimiValueDescriptor,
    val after: AimiValueDescriptor,
    val apply: (Preferences) -> Unit,
)

internal data class AimiValueDescriptor(
    val valueText: String? = null,
    val valueResId: TextRef? = null,
)

internal fun readAimiControlCenterDraft(preferences: Preferences): AimiControlCenterDraft {
    val snapshot = buildAimiControlCenterSnapshot(preferences)
    return AimiControlCenterDraft(
        protectionLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.Protection).normalizedScore),
        mealCaptureLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.MealCapture).normalizedScore),
        stabilityLevel = fiveStepIndex(snapshot.family(AimiBehaviorFamilyId.Stability).normalizedScore),
        physioLevel = threeStepIndex(snapshot.family(AimiBehaviorFamilyId.Physio).normalizedScore),
        autonomyMode = autonomyModeFromAutodrive(preferences),
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

internal fun projectionStatusSummaryResId(status: AimiProjectionStatus): TextRef =
    when (status) {
        AimiProjectionStatus.CoherentProfile -> ApsStrings.aimi_control_center_status_coherent_summary
        AimiProjectionStatus.MixedLegacy -> ApsStrings.aimi_control_center_status_mixed_summary
        AimiProjectionStatus.ExpertPersonalized -> ApsStrings.aimi_control_center_status_expert_summary
    }

internal fun AimiControlCenterSnapshot.family(id: AimiBehaviorFamilyId): AimiBehaviorFamilySnapshot =
    families.first { it.id == id }

/**
 * The Protection family writes the PK/PD relief floor and the red carpet restore threshold only.
 *
 * It used to write Max SMB, the high BG Max SMB and the two priority max IOB values too. Those are
 * dose limits: the person sets them on the Essentials page, and a family level must not move them
 * (step C1 of the settings plan).
 */
private fun buildProtectionPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val changes = listOfNotNull(
        ladderChange(preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, ApsStrings.oaps_aimi_pkpd_relief_factor_title, null),
        ladderChange(preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, listOf(0.60, 0.68, 0.75, 0.82, 0.90), currentLevel, targetLevel, ApsStrings.oaps_aimi_redcarpet_restore_title, null),
    )
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Protection,
        currentLabelResId = protectionLevelLabelForIndex(currentLevel),
        targetLabelResId = protectionLevelLabelForIndex(targetLevel),
        changes = changes,
    )
}

/**
 * The Meal capture family writes the hyper trajectory switches and the meal tuning values.
 *
 * It used to write the Autodrive max basal and the meal modes max basal too. Those are dose limits
 * the person sets, so a family level no longer moves them (step C1 of the settings plan).
 */
private fun buildMealCapturePlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val level = targetLevel.coerceIn(0, 4)
    val changes = listOfNotNull(
        booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, level >= 1),
        booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive, level >= 3),
        ladderChange(preferences, DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep, listOf(0.045, 0.060, 0.075, 0.090, 0.105), currentLevel, targetLevel, ApsStrings.aimi_mpc_u_per_kg_title, "U/kg/5m"),
        ladderChange(preferences, DoubleKey.OApsAIMIautodrivePrebolus, listOf(0.50, 0.80, 1.20, 1.80, 2.80), currentLevel, targetLevel, ApsStrings.prebolus_autodrive_mode_title, "U"),
        ladderChange(preferences, DoubleKey.OApsAIMIautodrivesmallPrebolus, listOf(0.05, 0.10, 0.20, 0.35, 0.60), currentLevel, targetLevel, ApsStrings.prebolussmall_autodrive_mode_title, "U"),
        ladderChange(preferences, DoubleKey.OApsAIMIHyperEstablishedDevMgdl, listOf(22.0, 18.0, 15.0, 12.0, 10.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
        ladderChange(preferences, DoubleKey.OApsAIMIHyperDeepDevMgdl, listOf(38.0, 32.0, 28.0, 24.0, 20.0), currentLevel, targetLevel, unit = "mg/dL", increasingSliderLevelRaisesValue = false),
    )
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.MealCapture,
        currentLabelResId = mealLevelLabelForIndex(currentLevel),
        targetLabelResId = mealLevelLabelForIndex(targetLevel),
        noteResId = ApsStrings.aimi_control_center_meal_apply_note,
        changes = changes,
    )
}

/**
 * Exercise damping rungs. The loop reads a value below [SmbDamping.DAMPING_FLOOR] as the floor
 * (since step P3), so the two lowest rungs (0.30 and 0.45) both act as 0.5.
 *
 * The ladder keeps its five old positions, so a stored value finds the same rung as before and a
 * step moves the same number of rungs. Only the written value changes: the step passes
 * `minValue = DAMPING_FLOOR`, so the two lowest rungs both write 0.5. The effective damping after
 * every step is therefore the same as before step C1, also from an old stored 0.30 (one step up
 * acts as 0.5, two steps give 0.60).
 */
private val EXERCISE_DAMPING_LADDER: List<Double> = listOf(0.30, 0.45, 0.60, 0.72, 0.85)

private fun buildStabilityPlan(
    preferences: Preferences,
    currentLevel: Int,
    targetLevel: Int,
): AimiFamilyWritebackPlan {
    val level = targetLevel.coerceIn(0, 4)
    // Tail floor ladder is shared: left/smoother = stronger damping (lower floor inside PKPD band).
    val tailFloorLadder = PkpdSmbTailDamping.STABILITY_FAMILY_FLOOR_LADDER
    val changes = listOfNotNull(
        // A stored value in the legacy zone (0.55 or lower) acts as 0.85, so the step starts from
        // the value in use. The start migration stores 0.85 once, but a TPO revert or an import
        // can bring a legacy value back while the app runs.
        ladderChange(
            preferences, DoubleKey.OApsAIMISmbTailDamping, tailFloorLadder, currentLevel, targetLevel,
            ApsStrings.oaps_aimi_smb_tail_damping_title, null, valueInUse = PkpdSmbTailDamping::effectiveStoredValue,
        ),
        ladderChange(
            preferences, DoubleKey.OApsAIMISmbExerciseDamping, EXERCISE_DAMPING_LADDER, currentLevel, targetLevel,
            ApsStrings.oaps_aimi_smb_exercise_damping_title, null, minValue = SmbDamping.DAMPING_FLOOR,
        ),
        ladderChange(preferences, DoubleKey.OApsAIMISmbLateFatDamping, listOf(0.40, 0.55, 0.70, 0.80, 0.90), currentLevel, targetLevel, ApsStrings.oaps_aimi_smb_late_fat_damping_title, null),
        booleanChange(preferences, BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled, true, ApsStrings.oaps_aimi_adaptive_basal_title),
        booleanChange(preferences, BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled, level >= 3),
        ladderChange(preferences, DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction, listOf(0.02, 0.04, 0.06, 0.08, 0.10), currentLevel, targetLevel, unit = null),
    )
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
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, false, ApsStrings.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, false, ApsStrings.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, false, ApsStrings.aimi_physio_hrv_enable_title),
        )
        1 -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, true, ApsStrings.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, true, ApsStrings.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, false, ApsStrings.aimi_physio_hrv_enable_title),
        )
        else -> listOfNotNull(
            booleanChange(preferences, BooleanKey.AimiPhysioAssistantEnable, true, ApsStrings.aimi_physio_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioSleepDataEnable, true, ApsStrings.aimi_physio_sleep_enable_title),
            booleanChange(preferences, BooleanKey.AimiPhysioHRVDataEnable, true, ApsStrings.aimi_physio_hrv_enable_title),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Physio,
        currentLabelResId = physioLevelLabelForIndex(currentLevel),
        targetLabelResId = physioLevelLabelForIndex(targetLevel),
        noteResId = ApsStrings.aimi_control_center_physio_apply_note,
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
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, false, ApsStrings.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, false),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.Recommendations -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, ApsStrings.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, false),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.AssistedApplication -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, ApsStrings.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, false),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, false),
        )
        AimiAutonomyMode.ControlledAuthority -> listOfNotNull(
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveActive, true, ApsStrings.oaps_aimi_enableMlautoDriveActive_title),
            booleanChange(preferences, BooleanKey.OApsAIMIHyperTrajectoryRelease, true),
            booleanChange(preferences, BooleanKey.OApsAIMIRecursiveBeliefAuthority, true),
            booleanChange(preferences, BooleanKey.OApsAIMIautoDriveAuthoritative, true),
        )
    }
    return AimiFamilyWritebackPlan(
        familyId = AimiBehaviorFamilyId.Autonomy,
        currentLabelResId = currentLevel.controlCenterLabel(),
        targetLabelResId = targetLevel.controlCenterLabel(),
        noteResId = ApsStrings.aimi_control_center_autonomy_apply_note,
        changes = changes,
    )
}

private fun booleanChange(
    preferences: Preferences,
    key: BooleanPreferenceKey,
    targetValue: Boolean,
    titleResId: TextRef = key.controlCenterTitleResId(),
): AimiPreferenceChange? {
    val currentValue = preferences.get(key)
    if (currentValue == targetValue) return null
    return AimiPreferenceChange(
        preferenceKey = key.key,
        titleResId = titleResId,
        before = AimiValueDescriptor(valueResId = if (currentValue) CoreUiStrings.yes else CoreUiStrings.no),
        after = AimiValueDescriptor(valueResId = if (targetValue) CoreUiStrings.yes else CoreUiStrings.no),
        apply = { prefs -> prefs.put(key, targetValue) },
    )
}

private fun ladderChange(
    preferences: Preferences,
    key: DoublePreferenceKey,
    ladder: List<Double>,
    currentLevel: Int,
    targetLevel: Int,
    titleResId: TextRef = key.controlCenterTitleResId(),
    unit: String?,
    increasingSliderLevelRaisesValue: Boolean = true,
    minValue: Double = key.min,
    valueInUse: (Double) -> Double = { it },
): AimiPreferenceChange? {
    if (ladder.isEmpty()) return null
    val delta = targetLevel - currentLevel
    if (delta == 0) return null
    // The step starts from the value the loop uses, which is not always the stored value.
    val currentValue = valueInUse(preferences.get(key))
    val currentIndex = ladder.indices.minByOrNull { index -> abs(ladder[index] - currentValue) } ?: 0
    val targetIndex = (currentIndex + delta).coerceIn(0, ladder.lastIndex)
    val valueShouldIncrease = if (increasingSliderLevelRaisesValue) delta > 0 else delta < 0
    val targetValue = resolveLadderTargetValue(
        ladder = ladder,
        key = key,
        currentValue = currentValue,
        ladderTargetValue = ladder[targetIndex],
        valueShouldIncrease = valueShouldIncrease,
    ).coerceIn(minValue, key.max)
    // A step never moves the wrong way. This can only happen when the stored value is already
    // outside [minValue, key.max], for example an old exercise damping of 0.30 and a step down.
    val movesWrongWay = if (valueShouldIncrease) targetValue < currentValue else targetValue > currentValue
    if (movesWrongWay) return null
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
    titleResId: TextRef,
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
