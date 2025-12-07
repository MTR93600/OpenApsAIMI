package app.aaps.plugins.aps.openAPSAIMI.comparison

import kotlin.math.max

/**
 * =============================================================================
 * PERFORMANCE SCORER
 * =============================================================================
 * 
 * Calculates a composite performance score from AlgorithmKpi.
 * 
 * Scoring Philosophy:
 * - Safety Score (50%): Heavily penalizes hypos, especially severe ones (<54)
 * - Control Score (30%): Rewards high TIR, penalizes extended highs
 * - Smoothness Score (20%): Rewards low variability and stable loop behavior
 * 
 * Each sub-score is 0-10, total score is weighted combination.
 * =============================================================================
 */
object PerformanceScorer {
    
    /**
     * Calculate performance score from KPIs.
     */
    fun score(kpi: AlgorithmKpi): PerformanceScore {
        val safety = calculateSafetyScore(kpi)
        val control = calculateControlScore(kpi)
        val smoothness = calculateSmoothnessScore(kpi)
        
        val total = safety * PerformanceScore.WEIGHT_SAFETY +
                   control * PerformanceScore.WEIGHT_CONTROL +
                   smoothness * PerformanceScore.WEIGHT_SMOOTHNESS
        
        return PerformanceScore(
            algo = kpi.algo,
            safetyScore = safety,
            controlScore = control,
            smoothnessScore = smoothness,
            totalScore = total
        )
    }
    
    /**
     * Safety Score (0-10)
     * 
     * Base: 10.0
     * Penalties:
     * - Each hypo event (<70): -1.0
     * - Each severe hypo event (<54): -2.5 (additional)
     * - Time below 70 >5%: -0.5 per extra %
     * - Time below 54 >1%: -1.0 per extra %
     * 
     * The goal is to strongly penalize any hypoglycemia events.
     */
    private fun calculateSafetyScore(kpi: AlgorithmKpi): Double {
        var score = 10.0
        
        // Hypo events penalty
        score -= kpi.hypoEventsCount * 1.0
        score -= kpi.severeHypoEventsCount * 2.5
        
        // Time below 70 penalty (beyond 5%)
        if (kpi.timeBelow70 > 5.0) {
            score -= (kpi.timeBelow70 - 5.0) * 0.5
        }
        
        // Time below 54 penalty (beyond 1%)
        if (kpi.timeBelow54 > 1.0) {
            score -= (kpi.timeBelow54 - 1.0) * 1.0
        }
        
        return max(0.0, score)
    }
    
    /**
     * Control Score (0-10)
     * 
     * Based on glycemic control metrics:
     * - TIR 70-180: Higher is better (scaled to 0-7 points)
     * - Time above 250: Penalized (up to -2 points)
     * - Mean BG deviation from 120: Penalized slightly
     * 
     * Perfect score = TIR >90%, no time >250, mean BG ~120
     */
    private fun calculateControlScore(kpi: AlgorithmKpi): Double {
        // TIR contribution: 0-7 points based on TIR 70-180
        // 90%+ = 7, 70% = 5.5, 50% = 4, below 50% drops faster
        val tirScore = when {
            kpi.tir70_180 >= 90 -> 7.0
            kpi.tir70_180 >= 70 -> 5.5 + (kpi.tir70_180 - 70) * 0.075  // 70% -> 5.5, 90% -> 7
            kpi.tir70_180 >= 50 -> 4.0 + (kpi.tir70_180 - 50) * 0.075  // 50% -> 4, 70% -> 5.5
            else -> kpi.tir70_180 * 0.08  // 0-50% scales 0-4
        }
        
        // Time above 250 penalty: -0.5 per 5% (max -2)
        val hyperPenalty = minOf(2.0, kpi.timeAbove250 / 5.0 * 0.5)
        
        // Mean BG deviation penalty: slight penalty if mean is far from 120
        val meanDeviation = kotlin.math.abs(kpi.meanBg - 120.0)
        val meanPenalty = when {
            meanDeviation < 10 -> 0.0
            meanDeviation < 30 -> 0.5
            meanDeviation < 50 -> 1.0
            else -> 1.5
        }
        
        val score = tirScore - hyperPenalty - meanPenalty + 3.0  // Base + TIR
        return max(0.0, minOf(10.0, score))
    }
    
    /**
     * Smoothness Score (0-10)
     * 
     * Measures loop stability and BG variability:
     * - CV% < 30: Good (higher score)
     * - Fewer TBR changes: Better
     * - Less zero-basal time: Better
     * 
     * A smooth loop runs predictably without excessive adjustments.
     */
    private fun calculateSmoothnessScore(kpi: AlgorithmKpi): Double {
        // CV penalty: CV <30% = 0 penalty, CV >50% = -4 penalty
        val cvPenalty = when {
            kpi.bgCv <= 30 -> 0.0
            kpi.bgCv <= 36 -> (kpi.bgCv - 30) * 0.1  // 30-36% = 0-0.6 penalty
            kpi.bgCv <= 50 -> 0.6 + (kpi.bgCv - 36) * 0.15  // 36-50% = 0.6-2.7 penalty
            else -> 2.7 + (kpi.bgCv - 50) * 0.1  // >50% = 2.7+ penalty
        }
        
        // TBR changes penalty: Normalized by hours (expect ~2-4 changes per hour is normal)
        val changesPerHour = if (kpi.durationHours > 0) kpi.tbrChangesCount / kpi.durationHours else 0.0
        val tbrPenalty = when {
            changesPerHour <= 4 -> 0.0
            changesPerHour <= 8 -> (changesPerHour - 4) * 0.25  // 4-8 = 0-1 penalty
            else -> 1.0 + (changesPerHour - 8) * 0.1  // >8 = 1+ penalty
        }
        
        // Zero basal penalty: More than 10% of time at zero is concerning
        val zeroPercent = if (kpi.durationHours > 0) {
            (kpi.zeroBasalMinutes / 60.0) / kpi.durationHours * 100
        } else 0.0
        val zeroPenalty = when {
            zeroPercent <= 5 -> 0.0
            zeroPercent <= 15 -> (zeroPercent - 5) * 0.1
            else -> 1.0 + (zeroPercent - 15) * 0.05
        }
        
        val score = 10.0 - cvPenalty - tbrPenalty - zeroPenalty
        return max(0.0, minOf(10.0, score))
    }
    
    /**
     * Compare two algorithms and generate a summary.
     */
    fun compare(
        aimiKpi: AlgorithmKpi,
        smbKpi: AlgorithmKpi,
        periodLabel: String
    ): AlgorithmsComparison {
        val aimiScore = score(aimiKpi)
        val smbScore = score(smbKpi)
        
        // Determine winner (null if tie within 0.3 points)
        val winner = when {
            aimiScore.totalScore - smbScore.totalScore > 0.3 -> AlgorithmType.AIMI
            smbScore.totalScore - aimiScore.totalScore > 0.3 -> AlgorithmType.OPENAPS_SMB
            else -> null
        }
        
        // Generate summary
        val summary = buildString {
            append("$periodLabel:\n")
            append("• TIR 70-180: ${f1(aimiKpi.tir70_180)}% vs ${f1(smbKpi.tir70_180)}%\n")
            append("• Time <70: ${f1(aimiKpi.timeBelow70)}% vs ${f1(smbKpi.timeBelow70)}%\n")
            append("• TDD: ${f1(aimiKpi.tdd)}U vs ${f1(smbKpi.tdd)}U\n")
            append("• Score: ${f1(aimiScore.totalScore)}/10 vs ${f1(smbScore.totalScore)}/10")
        }
        
        // Generate recommendation
        val recommendation = when (winner) {
            AlgorithmType.AIMI -> {
                val tirDiff = aimiKpi.tir70_180 - smbKpi.tir70_180
                val hypoDiff = smbKpi.timeBelow70 - aimiKpi.timeBelow70
                when {
                    tirDiff > 5 && hypoDiff > 1 -> "AIMI recommandé: +${f0(tirDiff)}% TIR et moins d'hypos"
                    tirDiff > 5 -> "AIMI recommandé: meilleur contrôle (+${f0(tirDiff)}% TIR)"
                    hypoDiff > 1 -> "AIMI recommandé: moins d'hypos (-${f1(hypoDiff)}%)"
                    else -> "AIMI légèrement supérieur"
                }
            }
            AlgorithmType.OPENAPS_SMB -> {
                val tirDiff = smbKpi.tir70_180 - aimiKpi.tir70_180
                when {
                    tirDiff > 5 -> "SMB classique recommandé: meilleur TIR"
                    else -> "SMB classique légèrement supérieur"
                }
            }
            null -> "Performance équivalente entre les deux algorithmes"
        }
        
        return AlgorithmsComparison(
            periodLabel = periodLabel,
            periodStart = aimiKpi.periodStart,
            periodEnd = aimiKpi.periodEnd,
            aimiKpi = aimiKpi,
            openApsSmbKpi = smbKpi,
            aimiScore = aimiScore,
            openApsSmbScore = smbScore,
            winner = winner,
            summary = summary,
            recommendation = recommendation
        )
    }
    
    private fun f1(value: Double) = "%.1f".format(java.util.Locale.US, value)
    private fun f0(value: Double) = "%.0f".format(java.util.Locale.US, value)
}
