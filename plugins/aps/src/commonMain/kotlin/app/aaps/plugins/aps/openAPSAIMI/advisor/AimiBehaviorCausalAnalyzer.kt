package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId
import app.aaps.plugins.aps.openAPSAIMI.compose.authorityRank
import app.aaps.plugins.aps.openAPSAIMI.compose.autonomyModeFromAutodrive
import kotlin.math.roundToInt

internal data class AimiBehaviorCausalInsight(
    val id: String,
    val primaryFamily: AimiBehaviorFamilyId,
    val secondaryFamilies: List<AimiBehaviorFamilyId> = emptyList(),
    val confidence: Float,
    val evidence: List<String> = emptyList(),
)

/**
 * Short notes for the AI coach prompt: patterns in the measured numbers, tagged with the family they
 * touch. They are prompt input only and never change a setting.
 *
 * Since step S2 a note stands only on measured numbers and on real switches. The Control Center
 * display levels (a guess computed from many settings) and its family suggestions are gone, so no
 * note can point to a family level any more.
 */
internal fun buildAimiBehaviorCausalInsights(
    preferences: Preferences,
    metrics: AdvisorMetrics,
): List<AimiBehaviorCausalInsight> {
    val insights = mutableListOf<AimiBehaviorCausalInsight>()
    // Insights are read as statements about the user's own period. With no measured numbers we say
    // nothing instead of describing an average patient.
    if (metrics.isInsufficient) return insights
    val variabilityCv = metrics.variabilityCv ?: return insights
    val timeBelow70 = metrics.timeBelow70 ?: return insights
    val timeAbove180 = metrics.timeAbove180 ?: return insights

    if (variabilityCv >= 0.36 && timeBelow70 >= 0.03 && timeAbove180 >= 0.12) {
        insights += AimiBehaviorCausalInsight(
            id = "yoyo_instability",
            primaryFamily = AimiBehaviorFamilyId.Stability,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Protection, AimiBehaviorFamilyId.MealCapture),
            confidence = 0.90f,
            evidence = listOf(
                "CV ${pct(variabilityCv)}% with lows ${pct(timeBelow70)}% and highs ${pct(timeAbove180)}%.",
                "This pattern usually reflects sequencing issues more than a single isolated threshold.",
            ),
        )
    }

    val autonomy = autonomyModeFromAutodrive(preferences)
    if (timeAbove180 >= 0.20 &&
        timeBelow70 <= 0.04 &&
        autonomy.authorityRank() < AimiAutonomyMode.AssistedApplication.authorityRank()
    ) {
        insights += AimiBehaviorCausalInsight(
            id = "meal_latency",
            primaryFamily = AimiBehaviorFamilyId.MealCapture,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Autonomy, AimiBehaviorFamilyId.Protection),
            confidence = 0.80f,
            evidence = listOf(
                "Hyper burden ${pct(timeAbove180)}% with low hypo pressure ${pct(timeBelow70)}%.",
                "Autodrive has neither the hyper trajectory release nor its authority switched on, so late undeclared-meal rises get no extra help.",
            ),
        )
    }

    if (timeBelow70 >= 0.05 || metrics.severeHypoEvents > 0) {
        insights += AimiBehaviorCausalInsight(
            id = "hypo_overshoot",
            primaryFamily = AimiBehaviorFamilyId.Protection,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Stability),
            confidence = if (timeBelow70 >= 0.07 || metrics.severeHypoEvents > 0) 0.92f else 0.82f,
            evidence = listOf(
                "Hypo burden ${pct(timeBelow70)}% with ${metrics.severeHypoEvents} severe events.",
                "The product should reduce overshoot before asking for more correction authority.",
            ),
        )
    }

    if (timeAbove180 >= 0.28 &&
        timeBelow70 <= 0.03
    ) {
        insights += AimiBehaviorCausalInsight(
            id = "hyper_passive",
            primaryFamily = AimiBehaviorFamilyId.Protection,
            secondaryFamilies = listOf(AimiBehaviorFamilyId.Autonomy),
            confidence = 0.84f,
            evidence = listOf(
                "Persistent hyperglycemia ${pct(timeAbove180)}% with limited low pressure ${pct(timeBelow70)}%.",
                "Highs last long while lows are rare: check the profile ISF, carb ratio and dose limits with the care team.",
            ),
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
