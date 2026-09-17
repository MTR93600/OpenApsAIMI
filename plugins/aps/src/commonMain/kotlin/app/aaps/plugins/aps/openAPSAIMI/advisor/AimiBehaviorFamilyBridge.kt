package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.AimiTuningContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextEngine
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningPlan
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyRegistry
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiControlCenterDraft
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiControlCenterPendingChanges
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiControlCenterAdvisorRecommendation
import app.aaps.plugins.aps.openAPSAIMI.compose.applyAimiControlCenterPendingChanges
import app.aaps.plugins.aps.openAPSAIMI.compose.buildAimiControlCenterAdvisorRecommendations
import app.aaps.plugins.aps.openAPSAIMI.compose.buildAimiControlCenterPendingChanges
import app.aaps.plugins.aps.openAPSAIMI.compose.readAimiControlCenterDraft
import app.aaps.plugins.aps.openAPSAIMI.compose.authorityRank
import kotlin.math.roundToInt

internal data class AimiFamilyBridgeSuggestion(
    val id: String,
    val titleResId: Int,
    val bodyResId: Int,
    val bodyArgs: List<Any> = emptyList(),
    val affectedFamilies: List<AimiBehaviorFamilyId>,
    val currentDraft: AimiControlCenterDraft,
    val targetDraft: AimiControlCenterDraft,
    val pendingChanges: AimiControlCenterPendingChanges,
    val driverKeys: List<PreferenceKey> = emptyList(),
)

internal fun buildAimiFamilyBridgeSuggestions(
    preferences: Preferences,
    metrics: AdvisorMetrics,
): List<AimiFamilyBridgeSuggestion> {
    val currentDraft = readAimiControlCenterDraft(preferences)
    val t3cBrittle = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
    val autoPlan = TuningContextEngine.computePlan(
        requestedContext = AimiTuningContext.AUTO_BALANCE,
        metrics = metrics,
        preferences = preferences,
        t3cBrittleMode = t3cBrittle,
    )
    val suggestions = mutableListOf<AimiFamilyBridgeSuggestion>()

    if (metrics.variabilityCv >= 0.36 && metrics.timeBelow70 >= 0.035 && metrics.timeAbove180 >= 0.12) {
        val draft = harmonizeDraft(
            preferences = preferences,
            draft = currentDraft.copy(
                stabilityLevel = (currentDraft.stabilityLevel - 1).coerceAtLeast(0),
                protectionLevel = if (metrics.timeBelow70 >= 0.045) {
                    (currentDraft.protectionLevel - 1).coerceAtLeast(0)
                } else {
                    currentDraft.protectionLevel
                },
            ),
        )
        suggestions += draftSuggestion(
            id = "swing_variability",
            titleResId = R.string.aimi_family_bridge_yoyo_title,
            bodyResId = R.string.aimi_family_bridge_yoyo_body,
            bodyArgs = listOf(
                pct(metrics.variabilityCv),
                pct(metrics.timeBelow70),
                pct(metrics.timeAbove180),
            ),
            currentDraft = currentDraft,
            targetDraft = draft,
            preferences = preferences,
            driverKeys = autoPlan.changes
                .filter { change ->
                    AimiBehaviorFamilyRegistry.familyForKey(change.key) in
                        setOf(AimiBehaviorFamilyId.Stability, AimiBehaviorFamilyId.Protection, AimiBehaviorFamilyId.MealCapture)
                }
                .map { it.key },
        )
    }

    when (autoPlan.effectiveContext) {
        AimiTuningContext.MEAL_RISE -> {
            val draft = harmonizeDraft(
                preferences = preferences,
                draft = currentDraft.copy(
                    mealCaptureLevel = (currentDraft.mealCaptureLevel + 1).coerceAtMost(4),
                    autonomyMode = if (currentDraft.autonomyMode.authorityRank() < AimiAutonomyMode.AssistedApplication.authorityRank()) {
                        AimiAutonomyMode.AssistedApplication
                    } else {
                        currentDraft.autonomyMode
                    },
                ),
            )
            suggestions += draftSuggestion(
                id = "meal_rise",
                titleResId = R.string.aimi_family_bridge_meal_rise_title,
                bodyResId = R.string.aimi_family_bridge_meal_rise_body,
                bodyArgs = listOf(
                    pct(metrics.timeAbove180),
                    pct(metrics.timeBelow70),
                ),
                currentDraft = currentDraft,
                targetDraft = draft,
                preferences = preferences,
                driverKeys = autoPlan.changes
                    .filter { change ->
                        AimiBehaviorFamilyRegistry.familyForKey(change.key) in
                            setOf(AimiBehaviorFamilyId.MealCapture, AimiBehaviorFamilyId.Autonomy, AimiBehaviorFamilyId.Protection)
                    }
                    .map { it.key },
            )
        }

        AimiTuningContext.HYPO_GUARD -> {
            val draft = harmonizeDraft(
                preferences = preferences,
                draft = currentDraft.copy(
                    protectionLevel = (currentDraft.protectionLevel - 1).coerceAtLeast(0),
                    stabilityLevel = (currentDraft.stabilityLevel - 1).coerceAtLeast(0),
                ),
            )
            suggestions += draftSuggestion(
                id = "hypo_guard",
                titleResId = R.string.aimi_family_bridge_hypo_guard_title,
                bodyResId = R.string.aimi_family_bridge_hypo_guard_body,
                bodyArgs = listOf(pct(metrics.timeBelow70)),
                currentDraft = currentDraft,
                targetDraft = draft,
                preferences = preferences,
                driverKeys = autoPlan.changes
                    .filter { change ->
                        AimiBehaviorFamilyRegistry.familyForKey(change.key) in
                            setOf(AimiBehaviorFamilyId.Protection, AimiBehaviorFamilyId.Stability, AimiBehaviorFamilyId.MealCapture)
                    }
                    .map { it.key },
            )
        }

        AimiTuningContext.HYPER_STABLE -> {
            val draft = harmonizeDraft(
                preferences = preferences,
                draft = currentDraft.copy(
                    protectionLevel = (currentDraft.protectionLevel + 1).coerceAtMost(4),
                    autonomyMode = if (metrics.timeAbove180 >= 0.30 && currentDraft.autonomyMode == AimiAutonomyMode.Observation) {
                        AimiAutonomyMode.Recommendations
                    } else {
                        currentDraft.autonomyMode
                    },
                ),
            )
            suggestions += draftSuggestion(
                id = "hyper_stable",
                titleResId = R.string.aimi_family_bridge_hyper_stable_title,
                bodyResId = R.string.aimi_family_bridge_hyper_stable_body,
                bodyArgs = listOf(
                    pct(metrics.timeAbove180),
                    pct(metrics.timeBelow70),
                ),
                currentDraft = currentDraft,
                targetDraft = draft,
                preferences = preferences,
                driverKeys = autoPlan.changes
                    .filter { change ->
                        AimiBehaviorFamilyRegistry.familyForKey(change.key) in
                            setOf(AimiBehaviorFamilyId.Protection, AimiBehaviorFamilyId.Autonomy)
                    }
                    .map { it.key },
            )
        }

        AimiTuningContext.MIXED_BALANCE -> {
            val draft = harmonizeDraft(
                preferences = preferences,
                draft = currentDraft.copy(
                    stabilityLevel = (currentDraft.stabilityLevel - 1).coerceAtLeast(0),
                    protectionLevel = if (metrics.timeBelow70 >= 0.04) {
                        (currentDraft.protectionLevel - 1).coerceAtLeast(0)
                    } else {
                        currentDraft.protectionLevel
                    },
                ),
            )
            suggestions += draftSuggestion(
                id = "mixed_balance",
                titleResId = R.string.aimi_family_bridge_mixed_title,
                bodyResId = R.string.aimi_family_bridge_mixed_body,
                bodyArgs = listOf(
                    pct(metrics.timeAbove180),
                    pct(metrics.timeBelow70),
                ),
                currentDraft = currentDraft,
                targetDraft = draft,
                preferences = preferences,
                driverKeys = autoPlan.changes
                    .filter { change ->
                        AimiBehaviorFamilyRegistry.familyForKey(change.key) in
                            setOf(AimiBehaviorFamilyId.Stability, AimiBehaviorFamilyId.Protection, AimiBehaviorFamilyId.MealCapture)
                    }
                    .map { it.key },
            )
        }

        AimiTuningContext.AUTO_BALANCE -> Unit
    }

    return suggestions
        .filter { it.pendingChanges.hasChanges }
        .distinctBy { it.id }
}

internal fun applyAimiFamilyBridgeSuggestion(
    preferences: Preferences,
    suggestion: AimiFamilyBridgeSuggestion,
) {
    applyAimiControlCenterPendingChanges(preferences, suggestion.pendingChanges)
}

private fun draftSuggestion(
    id: String,
    titleResId: Int,
    bodyResId: Int,
    bodyArgs: List<Any>,
    currentDraft: AimiControlCenterDraft,
    targetDraft: AimiControlCenterDraft,
    preferences: Preferences,
    driverKeys: List<PreferenceKey>,
): AimiFamilyBridgeSuggestion {
    val pending = buildAimiControlCenterPendingChanges(
        preferences = preferences,
        currentDraft = currentDraft,
        targetDraft = targetDraft,
    )
    return AimiFamilyBridgeSuggestion(
        id = id,
        titleResId = titleResId,
        bodyResId = bodyResId,
        bodyArgs = bodyArgs,
        affectedFamilies = affectedFamilies(currentDraft, targetDraft),
        currentDraft = currentDraft,
        targetDraft = targetDraft,
        pendingChanges = pending,
        driverKeys = driverKeys.distinctBy { it.key },
    )
}

private fun harmonizeDraft(
    preferences: Preferences,
    draft: AimiControlCenterDraft,
): AimiControlCenterDraft {
    var current = draft
    repeat(4) {
        val recommendations: List<AimiControlCenterAdvisorRecommendation> =
            buildAimiControlCenterAdvisorRecommendations(preferences, current)
        if (recommendations.isEmpty()) return current
        val updated = recommendations.fold(current) { acc, recommendation ->
            mergeDrafts(acc, recommendation.targetDraft)
        }
        if (updated == current) return current
        current = updated
    }
    return current
}

private fun mergeDrafts(
    base: AimiControlCenterDraft,
    update: AimiControlCenterDraft,
): AimiControlCenterDraft =
    base.copy(
        protectionLevel = maxOf(base.protectionLevel, update.protectionLevel),
        mealCaptureLevel = maxOf(base.mealCaptureLevel, update.mealCaptureLevel),
        stabilityLevel = minOf(base.stabilityLevel, update.stabilityLevel),
        physioLevel = maxOf(base.physioLevel, update.physioLevel),
        autonomyMode = if (update.autonomyMode.authorityRank() > base.autonomyMode.authorityRank()) {
            update.autonomyMode
        } else {
            base.autonomyMode
        },
    )

private fun affectedFamilies(
    current: AimiControlCenterDraft,
    target: AimiControlCenterDraft,
): List<AimiBehaviorFamilyId> = buildList {
    if (current.protectionLevel != target.protectionLevel) add(AimiBehaviorFamilyId.Protection)
    if (current.mealCaptureLevel != target.mealCaptureLevel) add(AimiBehaviorFamilyId.MealCapture)
    if (current.stabilityLevel != target.stabilityLevel) add(AimiBehaviorFamilyId.Stability)
    if (current.physioLevel != target.physioLevel) add(AimiBehaviorFamilyId.Physio)
    if (current.autonomyMode != target.autonomyMode) add(AimiBehaviorFamilyId.Autonomy)
}

private fun pct(value: Double): Int = (value * 100.0).roundToInt()
