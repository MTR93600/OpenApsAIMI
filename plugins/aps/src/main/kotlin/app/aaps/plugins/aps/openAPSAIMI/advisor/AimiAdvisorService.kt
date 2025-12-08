package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlin.math.roundToInt
import app.aaps.plugins.aps.R
import kotlin.math.max
import kotlin.math.min

/**
 * =============================================================================
 * AIMI ADVISOR SERVICE
 * =============================================================================
 * 
 * Analyzes metrics and provides scored recommendations.
 * Uses Resource IDs for localization support.
 * =============================================================================
 */
class AimiAdvisorService {

    /**
     * Generate a full advisor report for the specified period.
     */
    fun generateReport(periodDays: Int = 7): AdvisorReport {
        val metrics = collectMetrics(periodDays)
        val score = computeGlobalScore(metrics)
        val severity = classifySeverity(score)
        val recommendations = generateRecommendations(metrics)
        
        return AdvisorReport(
            generatedAt = System.currentTimeMillis(),
            metrics = metrics,
            overallScore = score,
            overallSeverity = severity,
            recommendations = recommendations
        )
    }

    /**
     * Collect metrics - DUMMY DATA for now.
     * TODO: Replace with real data from persistenceLayer
     */
    fun collectMetrics(periodDays: Int = 7): AdvisorMetrics {
        return AdvisorMetrics(
            periodLabel = "$periodDays derniers jours",
            tir70_180 = 0.78,
            tir70_140 = 0.55,
            timeBelow70 = 0.04,
            timeBelow54 = 0.00,
            timeAbove180 = 0.18,
            timeAbove250 = 0.05,
            meanBg = 135.0,
            tdd = 35.0,
            basalPercent = 0.48,
            hypoEvents = 0,
            severeHypoEvents = 0,
            hyperEvents = 4
        )
    }

    /**
     * Score global 0–10. 10 = perfect, 0 = critical.
     */
    private fun computeGlobalScore(metrics: AdvisorMetrics): Double {
        var score = 10.0

        // 1) TIR 70–180 : objective >= 80%
        val tir = metrics.tir70_180
        if (tir < 0.8) {
            val deficit = 0.8 - tir          
            score -= deficit * 40.0          // 2% missing TIR -> -0.8 pt
        }

        // 2) Hypos : time <70 (very penalizing)
        val below70 = metrics.timeBelow70
        if (below70 > 0.03) {                // tolerance ~3%
            val excess = below70 - 0.03
            score -= excess * 80.0           // 1% more -> -0.8 pt
        }

        // 3) Hypers : time >180
        val above180 = metrics.timeAbove180
        if (above180 > 0.20) {
            val excess = above180 - 0.20
            score -= excess * 40.0           // 5% more -> -2 pts
        }

        // 4) Severe Hypos
        if (metrics.timeBelow54 > 0.0 || metrics.severeHypoEvents > 0) {
            score -= 2.0
        }

        // 5) Basal Ratio if extreme (>60% or <35%)
        if (metrics.basalPercent > 0.60 || metrics.basalPercent < 0.35) {
            score -= 1.0
        }

        // Clamp 0–10
        score = max(0.0, min(10.0, score))

        // Round to 1 decimal
        return (score * 10.0).roundToInt() / 10.0
    }

    private fun classifySeverity(score: Double): AdvisorSeverity =
        when {
            score < 4.0 -> AdvisorSeverity.CRITICAL
            score < 7.0 -> AdvisorSeverity.WARNING
            else -> AdvisorSeverity.GOOD
        }

    /**
     * Generate recommendations based on metrics using Resource IDs.
     */
    fun generateRecommendations(metrics: AdvisorMetrics): List<AimiRecommendation> {
        val recs = mutableListOf<AimiRecommendation>()

        // 1) CRITICAL: Hypos
        if (metrics.timeBelow70 > 0.04 || metrics.severeHypoEvents > 0) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.SAFETY,
                priority = RecommendationPriority.CRITICAL,
                titleResId = R.string.aimi_adv_rec_hypos_title,
                descriptionResId = R.string.aimi_adv_rec_hypos_desc, // Format with % args in Activity
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_hypos_action_isf,
                    R.string.aimi_adv_rec_hypos_action_modes,
                    R.string.aimi_adv_rec_hypos_action_basal
                )
            )
        }

        // 2) HIGH: Poor Control (Low TIR but safe)
        if (metrics.tir70_180 < 0.70 && metrics.timeBelow70 <= 0.03) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.BASAL,
                priority = RecommendationPriority.HIGH,
                titleResId = R.string.aimi_adv_rec_control_title,
                descriptionResId = R.string.aimi_adv_rec_control_desc,
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_control_action_basal,
                    R.string.aimi_adv_rec_control_action_isf,
                    R.string.aimi_adv_rec_control_action_modes
                )
            )
        }

        // 3) MEDIUM: Hypers dominant
        if (metrics.timeAbove180 > 0.20 && metrics.timeBelow70 <= 0.03) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.ISF,
                priority = RecommendationPriority.MEDIUM,
                titleResId = R.string.aimi_adv_rec_hypers_title,
                descriptionResId = R.string.aimi_adv_rec_hypers_desc,
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_hypers_action_ratios,
                    R.string.aimi_adv_rec_hypers_action_autodrive,
                    R.string.aimi_adv_rec_hypers_action_factors
                )
            )
        }

        // 4) MEDIUM: Basal dominance
        if (metrics.basalPercent > 0.55) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.MEDIUM,
                titleResId = R.string.aimi_adv_rec_basal_title,
                descriptionResId = R.string.aimi_adv_rec_basal_desc,
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_basal_action_night,
                    R.string.aimi_adv_rec_basal_action_carbs
                )
            )
        }

        // 5) If nothing alarming -> positive message
        if (recs.isEmpty()) {
            recs += AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.LOW,
                titleResId = R.string.aimi_adv_rec_profile_ok_title,
                descriptionResId = R.string.aimi_adv_rec_profile_ok_desc,
                actionsResIds = listOf(
                    R.string.aimi_adv_rec_profile_ok_action_doc,
                    R.string.aimi_adv_rec_profile_ok_action_tune
                )
            )
        }

        return recs
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt()
}

