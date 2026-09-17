package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.annotation.StringRes
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR

internal data class AimiControlCenterAdvisorRecommendation(
    val id: String,
    @StringRes val titleResId: Int,
    @StringRes val bodyResId: Int,
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
            titleResId = R.string.aimi_control_center_advisor_meal_physio_guard_title,
            bodyResId = R.string.aimi_control_center_advisor_meal_physio_guard_body,
            affectedFamilies = listOf(AimiBehaviorFamilyId.Physio),
            targetDraft = draft.copy(physioLevel = 1),
        )
    }

    if (draft.mealCaptureLevel >= 3 && autonomyRank(draft.autonomyMode) < autonomyRank(AimiAutonomyMode.AssistedApplication)) {
        recommendations += AimiControlCenterAdvisorRecommendation(
            id = "meal_autonomy_alignment",
            titleResId = R.string.aimi_control_center_advisor_meal_autonomy_title,
            bodyResId = R.string.aimi_control_center_advisor_meal_autonomy_body,
            affectedFamilies = listOf(AimiBehaviorFamilyId.Autonomy),
            targetDraft = draft.copy(autonomyMode = AimiAutonomyMode.AssistedApplication),
        )
    }

    recommendedMealLevelForAutonomy(draft.autonomyMode)?.let { recommendedMealLevel ->
        if (draft.mealCaptureLevel < recommendedMealLevel) {
            recommendations += AimiControlCenterAdvisorRecommendation(
                id = "autonomy_meal_alignment",
                titleResId = R.string.aimi_control_center_advisor_autonomy_meal_title,
                bodyResId = R.string.aimi_control_center_advisor_autonomy_meal_body,
                affectedFamilies = listOf(AimiBehaviorFamilyId.MealCapture),
                targetDraft = draft.copy(mealCaptureLevel = recommendedMealLevel),
            )
        }
    }

    val sourceMode = preferences.get(AimiStringKey.ActivitySourceMode)
    val ouraConfigured = preferences.get(AimiStringKey.OuraPersonalAccessToken).isNotBlank()
    if (draft.physioLevel == 2 && sourceMode == UnifiedActivityProviderMTR.MODE_DISABLED && !ouraConfigured) {
        recommendations += AimiControlCenterAdvisorRecommendation(
            id = "physio_sources_alignment",
            titleResId = R.string.aimi_control_center_advisor_physio_sources_title,
            bodyResId = R.string.aimi_control_center_advisor_physio_sources_body,
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
