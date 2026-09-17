package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.keys.ACTIVITY_SOURCE_MODE_DISABLED
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey

internal data class AimiControlCenterAdvisorRecommendation(
    val id: String,
    val titleResId: TextRef,
    val bodyResId: TextRef,
    val affectedFamilies: List<AimiBehaviorFamilyId>,
    val targetDraft: AimiControlCenterDraft,
)

internal fun buildAimiControlCenterAdvisorRecommendations(
    preferences: Preferences,
    draft: AimiControlCenterDraft,
): List<AimiControlCenterAdvisorRecommendation> {
    val recommendations = mutableListOf<AimiControlCenterAdvisorRecommendation>()

    if (draft.mealCaptureLevel >= 4 && draft.physioLevel == 0) {
        recommendations += AimiControlCenterAdvisorRecommendation(
            id = "meal_physio_guard",
            titleResId = ApsStrings.aimi_control_center_advisor_meal_physio_guard_title,
            bodyResId = ApsStrings.aimi_control_center_advisor_meal_physio_guard_body,
            affectedFamilies = listOf(AimiBehaviorFamilyId.Physio),
            targetDraft = draft.copy(physioLevel = 1),
        )
    }

    if (draft.mealCaptureLevel >= 3 && autonomyRank(draft.autonomyMode) < autonomyRank(AimiAutonomyMode.AssistedApplication)) {
        recommendations += AimiControlCenterAdvisorRecommendation(
            id = "meal_autonomy_alignment",
            titleResId = ApsStrings.aimi_control_center_advisor_meal_autonomy_title,
            bodyResId = ApsStrings.aimi_control_center_advisor_meal_autonomy_body,
            affectedFamilies = listOf(AimiBehaviorFamilyId.Autonomy),
            targetDraft = draft.copy(autonomyMode = AimiAutonomyMode.AssistedApplication),
        )
    }

    recommendedMealLevelForAutonomy(draft.autonomyMode)?.let { recommendedMealLevel ->
        if (draft.mealCaptureLevel < recommendedMealLevel) {
            recommendations += AimiControlCenterAdvisorRecommendation(
                id = "autonomy_meal_alignment",
                titleResId = ApsStrings.aimi_control_center_advisor_autonomy_meal_title,
                bodyResId = ApsStrings.aimi_control_center_advisor_autonomy_meal_body,
                affectedFamilies = listOf(AimiBehaviorFamilyId.MealCapture),
                targetDraft = draft.copy(mealCaptureLevel = recommendedMealLevel),
            )
        }
    }

    val sourceMode = preferences.get(AimiStringKey.ActivitySourceMode)
    val ouraConfigured = preferences.get(AimiStringKey.OuraPersonalAccessToken).isNotBlank()
    if (draft.physioLevel == 2 && sourceMode == ACTIVITY_SOURCE_MODE_DISABLED && !ouraConfigured) {
        recommendations += AimiControlCenterAdvisorRecommendation(
            id = "physio_sources_alignment",
            titleResId = ApsStrings.aimi_control_center_advisor_physio_sources_title,
            bodyResId = ApsStrings.aimi_control_center_advisor_physio_sources_body,
            affectedFamilies = listOf(AimiBehaviorFamilyId.Physio),
            targetDraft = draft.copy(physioLevel = 1),
        )
    }

    return recommendations.distinctBy { it.id }
}

private fun autonomyRank(mode: AimiAutonomyMode): Int =
    when (mode) {
        AimiAutonomyMode.Observation -> 0
        AimiAutonomyMode.Recommendations -> 1
        AimiAutonomyMode.AssistedApplication -> 2
        AimiAutonomyMode.ControlledAuthority -> 3
    }

private fun recommendedMealLevelForAutonomy(mode: AimiAutonomyMode): Int? =
    when (mode) {
        AimiAutonomyMode.Observation -> null
        AimiAutonomyMode.Recommendations -> 2
        AimiAutonomyMode.AssistedApplication -> 2
        AimiAutonomyMode.ControlledAuthority -> 3
    }
