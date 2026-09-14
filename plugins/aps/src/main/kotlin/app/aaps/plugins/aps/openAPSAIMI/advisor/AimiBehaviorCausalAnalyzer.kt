package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId
import app.aaps.plugins.aps.openAPSAIMI.compose.authorityRank
import app.aaps.plugins.aps.openAPSAIMI.compose.readAimiControlCenterDraft
import kotlin.math.roundToInt

internal data class AimiBehaviorCausalInsight(
    val id: String,
    val titleResId: Int,
    val bodyResId: Int,
    val bodyArgs: List<Any> = emptyList(),
    val primaryFamily: AimiBehaviorFamilyId,
    val secondaryFamilies: List<AimiBehaviorFamilyId> = emptyList(),
    val confidence: Float,
    val evidence: List<String> = emptyList(),
    val relatedSuggestionId: String? = null,
)

internal fun buildAimiBehaviorCausalInsights(
    preferences: Preferences,
    metrics: AdvisorMetrics,
    familyBridgeSuggestions: List<AimiFamilyBridgeSuggestion>,
): List<AimiBehaviorCausalInsight> {
    val insights = mutableListOf<AimiBehaviorCausalInsight>()
    // Insights are read as statements about the user's own period. With no measured numbers we say
    // nothing instead of describing an average patient.
    if (metrics.isInsufficient) return insights
    val variabilityCv = metrics.variabilityCv ?: return insights
    val timeBelow70 = metrics.timeBelow70 ?: return insights
    val timeAbove180 = metrics.timeAbove180 ?: return insights

    val draft = readAimiControlCenterDraft(preferences)
    val availableSuggestionIds = familyBridgeSuggestions.map { it.id }.toSet()

    if (variabilityCv >= 0.36 && timeBelow70 >= 0.03 && timeAbove180 >= 0.12) {
        insights += AimiBehaviorCausalInsight(
            id = "yoyo_instability",
            titleResId = R.string.aimi_behavior_causal_yoyo_title,
            bodyResId = R.string.aimi_behavior_causal_yoyo_body,
            bodyArgs = listOf(
                pct(variabilityCv),
                pct(timeBelow70),
                pct(timeAbove180),
            ),
            primaryFamily = AimiBehaviorFamilyId.Stability,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Protection, AimiBehaviorFamilyId.MealCapture),
            confidence = 0.90f,
            evidence = listOf(
                "CV ${pct(variabilityCv)}% with lows ${pct(timeBelow70)}% and highs ${pct(timeAbove180)}%.",
                "This pattern usually reflects sequencing issues more than a single isolated threshold.",
            ),
            relatedSuggestionId = "swing_variability".takeIf { it in availableSuggestionIds },
        )
    }

    if (timeAbove180 >= 0.20 &&
        timeBelow70 <= 0.04 &&
        (draft.mealCaptureLevel <= 2 || draft.autonomyMode.authorityRank() < AimiAutonomyMode.AssistedApplication.authorityRank())
    ) {
        insights += AimiBehaviorCausalInsight(
            id = "meal_latency",
            titleResId = R.string.aimi_behavior_causal_meal_latency_title,
            bodyResId = R.string.aimi_behavior_causal_meal_latency_body,
            bodyArgs = listOf(
                pct(timeAbove180),
                pct(timeBelow70),
            ),
            primaryFamily = AimiBehaviorFamilyId.MealCapture,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Autonomy, AimiBehaviorFamilyId.Protection),
            confidence = if (draft.mealCaptureLevel <= 1) 0.88f else 0.80f,
            evidence = listOf(
                "Hyper burden ${pct(timeAbove180)}% with low hypo pressure ${pct(timeBelow70)}%.",
                "Current meal posture is not strongly assertive enough for late undeclared-meal trajectories.",
            ),
            relatedSuggestionId = "meal_rise".takeIf { it in availableSuggestionIds },
        )
    }

    if (timeBelow70 >= 0.05 || metrics.severeHypoEvents > 0) {
        insights += AimiBehaviorCausalInsight(
            id = "hypo_overshoot",
            titleResId = R.string.aimi_behavior_causal_hypo_title,
            bodyResId = R.string.aimi_behavior_causal_hypo_body,
            bodyArgs = listOf(
                pct(timeBelow70),
                metrics.severeHypoEvents,
            ),
            primaryFamily = AimiBehaviorFamilyId.Protection,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Stability),
            confidence = if (timeBelow70 >= 0.07 || metrics.severeHypoEvents > 0) 0.92f else 0.82f,
            evidence = listOf(
                "Hypo burden ${pct(timeBelow70)}% with ${metrics.severeHypoEvents} severe events.",
                "The product should reduce overshoot before asking for more correction authority.",
            ),
            relatedSuggestionId = "hypo_guard".takeIf { it in availableSuggestionIds },
        )
    }

    if (timeAbove180 >= 0.28 &&
        timeBelow70 <= 0.03 &&
        draft.protectionLevel <= 2
    ) {
        insights += AimiBehaviorCausalInsight(
            id = "hyper_passive",
            titleResId = R.string.aimi_behavior_causal_hyper_passive_title,
            bodyResId = R.string.aimi_behavior_causal_hyper_passive_body,
            bodyArgs = listOf(
                pct(timeAbove180),
                pct(timeBelow70),
            ),
            primaryFamily = AimiBehaviorFamilyId.Protection,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Autonomy),
            confidence = 0.84f,
            evidence = listOf(
                "Persistent hyperglycemia ${pct(timeAbove180)}% with limited low pressure ${pct(timeBelow70)}%.",
                "This usually means correction headroom is too conservative for the current profile.",
            ),
            relatedSuggestionId = "hyper_stable".takeIf { it in availableSuggestionIds },
        )
    }

    if (draft.physioLevel == 0 &&
        draft.mealCaptureLevel >= 3 &&
        variabilityCv >= 0.28
    ) {
        insights += AimiBehaviorCausalInsight(
            id = "physio_ambiguity",
            titleResId = R.string.aimi_behavior_causal_physio_title,
            bodyResId = R.string.aimi_behavior_causal_physio_body,
            bodyArgs = listOf(
                pct(variabilityCv),
                pct(timeAbove180),
            ),
            primaryFamily = AimiBehaviorFamilyId.Physio,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.MealCapture),
            confidence = 0.76f,
            evidence = listOf(
                "Meal posture is assertive while physio influence remains low.",
                "In ambiguous rises, this increases the risk of treating endocrine or cortisol-driven signals like meals.",
            ),
            relatedSuggestionId = "mixed_balance".takeIf { it in availableSuggestionIds },
        )
    }

    return insights
        .sortedByDescending { it.confidence }
        .take(3)
}

internal fun formatAimiBehaviorCausalInsightsForCoach(
    insights: List<AimiBehaviorCausalInsight>,
    familyTitle: (AimiBehaviorFamilyId) -> String,
): String {
    if (insights.isEmpty()) return "No strong family-level causal mismatch detected."
    return insights.joinToString(separator = "\n") { insight ->
        val families = buildList {
            add(familyTitle(insight.primaryFamily))
            addAll(insight.secondaryFamilies.map(familyTitle))
        }.distinct().joinToString(", ")
        val evidence = insight.evidence.joinToString(" | ")
        "- ${insight.id}: families=$families; confidence=${(insight.confidence * 100).roundToInt()}%; evidence=$evidence"
    }
}

private fun pct(value: Double): Int =
    (value * 100.0).roundToInt()
