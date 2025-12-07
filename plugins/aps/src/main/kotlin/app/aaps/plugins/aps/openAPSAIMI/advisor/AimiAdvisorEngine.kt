package app.aaps.plugins.aps.openAPSAIMI.advisor

import kotlin.math.roundToInt

/**
 * =============================================================================
 * AIMI ADVISOR ENGINE
 * =============================================================================
 * 
 * Rule-based engine that analyzes metrics and generates recommendations.
 * 
 * Philosophy:
 * 1. SAFETY FIRST: Any hypo events trigger immediate safety recommendations
 * 2. CONTROL NEXT: Improve TIR if safe
 * 3. OPTIMIZATION: Fine-tune once safe and controlled
 * =============================================================================
 */
object AimiAdvisorEngine {
    
    /**
     * Generate recommendations from metrics.
     */
    fun analyze(metrics: AdvisorMetrics): AdvisorReport {
        val recommendations = mutableListOf<AimiRecommendation>()
        
        // === LAYER 1: SAFETY (Critical) ===
        analyzeSafety(metrics, recommendations)
        
        // === LAYER 2: CONTROL ===
        analyzeControl(metrics, recommendations)
        
        // === LAYER 3: INSULIN EFFICIENCY ===
        analyzeInsulinEfficiency(metrics, recommendations)
        
        // === LAYER 4: PROFILE QUALITY ===
        analyzeProfileQuality(metrics, recommendations)
        
        // === LAYER 5: ADVANCED FEATURES ===
        analyzeAdvancedFeatures(metrics, recommendations)
        
        // If no issues found, add positive reinforcement
        if (recommendations.isEmpty()) {
            recommendations.add(createPositiveRecommendation(metrics))
        }
        
        // Sort by priority
        val sortedRecs = recommendations.sortedBy { it.priority.ordinal }
        
        // Calculate overall score
        val score = calculateOverallScore(metrics, sortedRecs)
        val assessment = when {
            score >= 8.0 -> "Excellent"
            score >= 6.5 -> "Bon"
            score >= 5.0 -> "À améliorer"
            else -> "Nécessite attention"
        }
        
        return AdvisorReport(
            generatedAt = System.currentTimeMillis(),
            metrics = metrics,
            overallScore = score,
            overallAssessment = assessment,
            recommendations = sortedRecs,
            summary = formatSummary(metrics, score, assessment)
        )
    }
    
    // =========================================================================
    // SAFETY ANALYSIS
    // =========================================================================
    
    private fun analyzeSafety(metrics: AdvisorMetrics, recs: MutableList<AimiRecommendation>) {
        // Severe hypos - CRITICAL
        if (metrics.severeHypoEvents > 0 || metrics.timeBelow54 > 0.01) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.SAFETY,
                priority = RecommendationPriority.CRITICAL,
                title = "⚠️ Hypos sévères détectées",
                description = "Temps <54 mg/dL: ${pct(metrics.timeBelow54)}%, Épisodes sévères: ${metrics.severeHypoEvents}. " +
                    "Réduire immédiatement l'agressivité de la boucle.",
                suggestedChanges = listOf(
                    "Réduire MaxSMB de 20-30%",
                    "Augmenter la cible basse (min_bg) de 10-15 mg/dL",
                    "Réduire Unified Reactivity globalFactor (-0.1 à -0.2)",
                    "Vérifier les basales nocturnes"
                ),
                affectedSettings = listOf("OApsAIMIMaxSMB", "min_bg", "globalFactor")
            ))
        }
        
        // Frequent hypos - HIGH priority
        if (metrics.hypoEvents >= 3 || metrics.timeBelow70 > 0.04) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.SAFETY,
                priority = RecommendationPriority.HIGH,
                title = "Hypos fréquentes",
                description = "Temps <70 mg/dL: ${pct(metrics.timeBelow70)}%, Épisodes: ${metrics.hypoEvents} sur ${metrics.periodDays}j. " +
                    "Objectif: <4% et <1 épisode/jour.",
                suggestedChanges = listOf(
                    "Réduire MaxSMB de 10-15%",
                    "Réduire les facteurs repas (Breakfast/Lunch/DinnerFactor) si hypos post-prandiales",
                    "Activer/ajuster le Basal Floor"
                ),
                affectedSettings = listOf("OApsAIMIMaxSMB", "meal_factors")
            ))
        }
    }
    
    // =========================================================================
    // CONTROL ANALYSIS
    // =========================================================================
    
    private fun analyzeControl(metrics: AdvisorMetrics, recs: MutableList<AimiRecommendation>) {
        // Poor TIR without hypos - profile too conservative
        if (metrics.tir70_180 < 0.65 && metrics.timeBelow70 < 0.03) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.BASAL,
                priority = RecommendationPriority.HIGH,
                title = "Contrôle insuffisant (TIR ${pct(metrics.tir70_180)}%)",
                description = "Le TIR est bas mais peu d'hypos, suggérant un profil trop conservateur. " +
                    "Objectif: TIR >70%.",
                suggestedChanges = listOf(
                    "Augmenter la basale de 5-10% sur les plages hyperglycémiques",
                    "Réduire l'ISF de 5-10% (plus d'insuline pour corriger)",
                    "Augmenter MaxSMB si SMB rarement déclenchés"
                ),
                affectedSettings = listOf("basal_rate", "sens", "OApsAIMIMaxSMB")
            ))
        }
        
        // Moderate TIR with room for improvement
        if (metrics.tir70_180 in 0.65..0.75 && metrics.timeBelow70 < 0.03) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.ISF,
                priority = RecommendationPriority.MEDIUM,
                title = "Marge d'amélioration du TIR",
                description = "TIR actuel: ${pct(metrics.tir70_180)}%. Peut être amélioré prudemment.",
                suggestedChanges = listOf(
                    "Réduire légèrement l'ISF (-5%)",
                    "Activer/augmenter AutoDrive si repas non annoncés fréquents",
                    "Vérifier les modes repas sont correctement utilisés"
                ),
                affectedSettings = listOf("sens", "OApsAIMIautoDrive")
            ))
        }
        
        // Significant time above 180
        if (metrics.timeAbove180 > 0.25 && metrics.timeBelow70 < 0.03) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.MODES,
                priority = RecommendationPriority.HIGH,
                title = "Temps élevé en hyperglycémie",
                description = "Temps >180 mg/dL: ${pct(metrics.timeAbove180)}%. Vérifier la couverture des repas.",
                suggestedChanges = listOf(
                    "Augmenter les facteurs repas (MealFactor, HighCarbFactor)",
                    "Réduire le délai SMB après repas (SMBInterval)",
                    "Vérifier le ratio glucides/insuline (IC)"
                ),
                affectedSettings = listOf("meal_factors", "SMBInterval", "carb_ratio")
            ))
        }
        
        // Severe hyperglycemia events
        if (metrics.hyperEvents > 2 || metrics.timeAbove250 > 0.05) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.ISF,
                priority = RecommendationPriority.HIGH,
                title = "Épisodes d'hyperglycémie sévère",
                description = "Temps >250 mg/dL: ${pct(metrics.timeAbove250)}%, Épisodes: ${metrics.hyperEvents}. " +
                    "Vérifier l'agressivité aux hautes glycémies.",
                suggestedChanges = listOf(
                    "Réduire l'ISF pour les hautes glycémies (dynISF)",
                    "Augmenter MaxSMB si les SMB sont plafonnés trop bas",
                    "Vérifier que les SMB sont activés (enableSMB_always)"
                ),
                affectedSettings = listOf("sens", "OApsAIMIMaxSMB", "enableSMB_always")
            ))
        }
    }
    
    // =========================================================================
    // INSULIN EFFICIENCY ANALYSIS
    // =========================================================================
    
    private fun analyzeInsulinEfficiency(metrics: AdvisorMetrics, recs: MutableList<AimiRecommendation>) {
        // Good TIR but high TDD - could optimize
        if (metrics.tir70_180 > 0.80 && metrics.tdd > 40.0) {  // Adjust threshold based on user
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.SMB,
                priority = RecommendationPriority.LOW,
                title = "Consommation d'insuline élevée",
                description = "TDD: ${metrics.tdd.roundToInt()}U avec bon TIR. " +
                    "Possible d'optimiser pour réduire la TDD.",
                suggestedChanges = listOf(
                    "Réduire légèrement MaxBasal (-5%)",
                    "Réduire la fréquence SMB (augmenter SMBInterval)",
                    "Augmenter légèrement l'ISF (+5%)"
                ),
                affectedSettings = listOf("max_basal", "SMBInterval", "sens")
            ))
        }
        
        // Very low SMB usage might indicate SMB blocked
        if (metrics.smbPercent < 0.10 && metrics.tir70_180 < 0.75) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.SMB,
                priority = RecommendationPriority.MEDIUM,
                title = "SMB peu utilisés",
                description = "Seulement ${pct(metrics.smbPercent)}% de la TDD en SMB. " +
                    "Vérifier que les SMB sont correctement activés.",
                suggestedChanges = listOf(
                    "Vérifier enableSMB_always ou enableSMB_with_COB",
                    "Augmenter MaxSMB si trop bas",
                    "Réduire SMBInterval pour plus de réactivité"
                ),
                affectedSettings = listOf("enableSMB_always", "OApsAIMIMaxSMB", "SMBInterval")
            ))
        }
    }
    
    // =========================================================================
    // PROFILE QUALITY ANALYSIS
    // =========================================================================
    
    private fun analyzeProfileQuality(metrics: AdvisorMetrics, recs: MutableList<AimiRecommendation>) {
        // Basal-heavy profile
        if (metrics.basalPercent > 0.60) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.MEDIUM,
                title = "Profil basal dominant",
                description = "Basale = ${pct(metrics.basalPercent)}% de la TDD. " +
                    "Au-delà de 50-60%, suggère une basale possiblement trop élevée.",
                suggestedChanges = listOf(
                    "Revoir les basales nocturnes (souvent surdosées)",
                    "Vérifier le ratio IC - peut nécessiter plus de bolus repas",
                    "Réduire la basale sur les créneaux où BG tend bas"
                ),
                affectedSettings = listOf("basal_rate", "carb_ratio")
            ))
        }
        
        // High CV - unstable control
        if (metrics.bgCv > 40.0) {
            recs.add(AimiRecommendation(
                domain = RecommendationDomain.PROFILE_QUALITY,
                priority = RecommendationPriority.MEDIUM,
                title = "Variabilité glycémique élevée",
                description = "CV = ${metrics.bgCv.roundToInt()}%. Objectif: <36%.",
                suggestedChanges = listOf(
                    "Réduire MaxSMB pour moins d'oscillations",
                    "Augmenter SMBInterval pour laisser l'insuline agir",
                    "Activer/ajuster le Basal Floor pour éviter les creux"
                ),
                affectedSettings = listOf("OApsAIMIMaxSMB", "SMBInterval")
            ))
        }
    }
    
    // =========================================================================
    // ADVANCED FEATURES ANALYSIS
    // =========================================================================
    
    private fun analyzeAdvancedFeatures(metrics: AdvisorMetrics, recs: MutableList<AimiRecommendation>) {
        // Activity detected but not fully utilized
        if (metrics.activityDaysDetected > 0 && metrics.avgActivityScore != null) {
            if (metrics.avgActivityScore < 3.0 && metrics.hypoEvents > 1) {
                recs.add(AimiRecommendation(
                    domain = RecommendationDomain.ACTIVITY,
                    priority = RecommendationPriority.MEDIUM,
                    title = "Module Activité sous-utilisé",
                    description = "Activité détectée mais score moyen faible (${metrics.avgActivityScore}). " +
                        "Possible hypos liées à l'activité.",
                    suggestedChanges = listOf(
                        "Vérifier que les capteurs (pas/FC) sont correctement configurés",
                        "Ajuster les seuils d'activité (MODERATE/INTENSE)",
                        "Activer le mode Recovery plus agressif"
                    ),
                    affectedSettings = listOf("activity_thresholds")
                ))
            }
        }
    }
    
    // =========================================================================
    // POSITIVE REINFORCEMENT
    // =========================================================================
    
    private fun createPositiveRecommendation(metrics: AdvisorMetrics): AimiRecommendation {
        return AimiRecommendation(
            domain = RecommendationDomain.PROFILE_QUALITY,
            priority = RecommendationPriority.LOW,
            title = "✅ Profil bien équilibré",
            description = "TIR: ${pct(metrics.tir70_180)}%, Hypos: ${pct(metrics.timeBelow70)}%, CV: ${metrics.bgCv.roundToInt()}%. " +
                "Les indicateurs sont cohérents. Aucune modification majeure nécessaire.",
            suggestedChanges = listOf(
                "Documenter cette configuration comme référence",
                "Affiner uniquement les plages horaires spécifiques si besoin",
                "Tester prudemment les nouvelles fonctionnalités"
            )
        )
    }
    
    // =========================================================================
    // SCORING
    // =========================================================================
    
    private fun calculateOverallScore(metrics: AdvisorMetrics, recs: List<AimiRecommendation>): Double {
        var score = 10.0
        
        // TIR contribution (0-4 points)
        score -= (0.90 - metrics.tir70_180).coerceAtLeast(0.0) * 10  // Lose up to 4 points
        
        // Hypo penalty (up to -3 points)
        score -= metrics.timeBelow70 * 30  // 0.10 (10%) would be -3 points
        score -= metrics.severeHypoEvents * 0.5
        
        // Hyper penalty (up to -2 points)
        score -= metrics.timeAbove250 * 20
        
        // Variability penalty (up to -1 point)
        if (metrics.bgCv > 36) score -= (metrics.bgCv - 36) / 14  // CV 50% = -1 point
        
        // Recommendation count penalty
        val criticalCount = recs.count { it.priority == RecommendationPriority.CRITICAL }
        val highCount = recs.count { it.priority == RecommendationPriority.HIGH }
        score -= criticalCount * 1.0
        score -= highCount * 0.3
        
        return score.coerceIn(0.0, 10.0)
    }
    
    // =========================================================================
    // FORMATTING
    // =========================================================================
    
    private fun formatSummary(metrics: AdvisorMetrics, score: Double, assessment: String): String {
        return buildString {
            append("🎯 Score global: ${"%.1f".format(score)}/10 ($assessment)\n\n")
            append("📊 Période: ${metrics.periodLabel}\n")
            append("• TIR 70-180: ${pct(metrics.tir70_180)}%\n")
            append("• TIR 70-140: ${pct(metrics.tir70_140)}%\n")
            append("• Temps <70: ${pct(metrics.timeBelow70)}%\n")
            append("• Temps >180: ${pct(metrics.timeAbove180)}%\n")
            append("• Glycémie moyenne: ${metrics.meanBg.roundToInt()} mg/dL\n")
            append("• CV: ${metrics.bgCv.roundToInt()}%\n")
            append("• TDD: ${metrics.tdd.roundToInt()} U (Basal ${pct(metrics.basalPercent)}%)\n")
        }
    }
    
    private fun pct(value: Double): Int = (value * 100).roundToInt()
}
