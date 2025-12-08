package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlin.math.roundToInt

/**
 * =============================================================================
 * AIMI ADVISOR SERVICE - SIMPLE & ROBUST VERSION
 * =============================================================================
 * 
 * NO Dagger injection - created manually in the Activity.
 * This guarantees no injection-related crashes.
 * 
 * For now: returns dummy metrics for testing.
 * TODO: Wire to real data sources (persistenceLayer, TIR calculator, etc.)
 * =============================================================================
 */
class AimiAdvisorService {

    /**
     * Generate a full advisor report for the specified period.
     * @param periodDays Number of days to analyze (default: 7)
     */
    fun generateReport(periodDays: Int = 7): AdvisorReport {
        val metrics = collectMetrics(periodDays)
        val recommendations = generateRecommendations(metrics)
        val score = calculateScore(metrics)
        
        return AdvisorReport(
            generatedAt = System.currentTimeMillis(),
            metrics = metrics,
            overallScore = score,
            overallAssessment = getAssessmentLabel(score),
            recommendations = recommendations,
            summary = formatSummary(metrics)
        )
    }

    /**
     * Collect metrics - DUMMY DATA for now to avoid crashes.
     * Replace with real data collection when wiring to AAPS database.
     */
    fun collectMetrics(periodDays: Int = 7): AdvisorMetrics {
        // TODO: Replace with real data from persistenceLayer
        return AdvisorMetrics(
            periodLabel = "$periodDays derniers jours",
            tir70_180 = 0.78,
            tir70_140 = 0.55,
            timeBelow70 = 0.04,
            timeBelow54 = 0.005,
            timeAbove180 = 0.18,
            timeAbove250 = 0.03,
            meanBg = 135.0,
            tdd = 35.0,
            basalPercent = 0.48,
            hypoEvents = 2,
            severeHypoEvents = 0,
            hyperEvents = 4
        )
    }

    /**
     * Generate recommendations based on metrics.
     */
    fun generateRecommendations(metrics: AdvisorMetrics): List<AimiRecommendation> {
        val recs = mutableListOf<AimiRecommendation>()

        // 1) CRITICAL: Severe hypos
        if (metrics.timeBelow54 > 0.01 || metrics.severeHypoEvents > 0) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.SAFETY,
                priority = RecommendationPriority.CRITICAL,
                title = "Hypos sévères détectées",
                description = "${percent(metrics.timeBelow54)}% du temps sous 54 mg/dL, " +
                    "${metrics.severeHypoEvents} épisodes sévères. Priorité : réduire l'agressivité.",
                suggestedChanges = listOf(
                    "Réduire MaxSMB de 10-20%",
                    "Réduire les facteurs des modes repas",
                    "Vérifier les basales nocturnes"
                )
            )
        }

        // 2) HIGH: Poor control with few hypos
        if (metrics.tir70_180 < 0.70 && metrics.timeBelow70 < 0.03) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.BASAL,
                priority = RecommendationPriority.HIGH,
                title = "Contrôle insuffisant",
                description = "TIR 70-180 = ${percent(metrics.tir70_180)}% avec peu d'hypos. " +
                    "Le profil est trop conservateur.",
                suggestedChanges = listOf(
                    "Augmenter la basale de 5-10%",
                    "Réduire l'ISF (plus d'insuline par correction)",
                    "Utiliser les modes repas systématiquement"
                )
            )
        }

        // 3) MEDIUM: High time above 180
        if (metrics.timeAbove180 > 0.25) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.ISF,
                priority = RecommendationPriority.MEDIUM,
                title = "Temps élevé au-dessus de 180",
                description = "${percent(metrics.timeAbove180)}% du temps > 180 mg/dL.",
                suggestedChanges = listOf(
                    "Vérifier les ratios glucides/insuline",
                    "Activer/ajuster AutoDrive",
                    "Augmenter les facteurs des modes repas"
                )
            )
        }

        // 4) MEDIUM: Basal too dominant
        if (metrics.basalPercent > 0.55) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.MEDIUM,
                title = "Basale dominante",
                description = "Basale = ${percent(metrics.basalPercent)}% de TDD. " +
                    "Peut indiquer des basales trop élevées ou des bolus insuffisants.",
                suggestedChanges = listOf(
                    "Revoir les basales nocturnes",
                    "Améliorer le comptage des glucides"
                )
            )
        }

        // 5) If nothing alarming -> positive message
        if (recs.isEmpty()) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.LOW,
                title = "Profil bien équilibré ✓",
                description = "Les indicateurs sont cohérents. Continuez ainsi !",
                suggestedChanges = listOf(
                    "Documenter cette configuration",
                    "Ajuster uniquement les tranches problématiques"
                )
            )
        }

        return recs
    }

    /**
     * Calculate overall score (0-10).
     */
    private fun calculateScore(metrics: AdvisorMetrics): Double {
        var score = 10.0

        // Penalize for hypos (severe = worst)
        score -= metrics.timeBelow54 * 100  // -1 per 1%
        score -= metrics.severeHypoEvents * 0.5
        score -= metrics.timeBelow70 * 30   // -0.3 per 1%

        // Penalize for hypers
        score -= metrics.timeAbove180 * 20  // -0.2 per 1%
        score -= metrics.timeAbove250 * 50  // -0.5 per 1%

        // Bonus for good TIR
        if (metrics.tir70_180 > 0.80) score += 0.5
        if (metrics.tir70_140 > 0.60) score += 0.5

        return score.coerceIn(0.0, 10.0)
    }

    private fun getAssessmentLabel(score: Double): String = when {
        score >= 8.5 -> "Excellent"
        score >= 7.0 -> "Bon"
        score >= 5.5 -> "À améliorer"
        score >= 4.0 -> "Attention requise"
        else -> "Action urgente"
    }

    /**
     * Format summary text.
     */
    fun formatSummary(metrics: AdvisorMetrics): String = buildString {
        append("Période : ${metrics.periodLabel}\n\n")
        append("TIR 70-180 : ${percent(metrics.tir70_180)}%\n")
        append("TIR 70-140 : ${percent(metrics.tir70_140)}%\n")
        append("Temps <70 : ${percent(metrics.timeBelow70)}%\n")
        append("Temps >180 : ${percent(metrics.timeAbove180)}%\n")
        append("Glycémie moyenne : ${metrics.meanBg.roundToInt()} mg/dL\n")
        append("TDD : ${metrics.tdd} U (basal ${percent(metrics.basalPercent)}%)")
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt()
}
