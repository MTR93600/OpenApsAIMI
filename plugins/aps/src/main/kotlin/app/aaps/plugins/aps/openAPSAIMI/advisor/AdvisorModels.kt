package app.aaps.plugins.aps.openAPSAIMI.advisor

/**
 * =============================================================================
 * AIMI ADVISOR DATA MODELS
 * =============================================================================
 * 
 * Data classes for the AIMI Profile Advisor feature.
 * Analyzes user's glycemic performance and generates actionable recommendations.
 * =============================================================================
 */

/**
 * Metrics collected for analysis.
 * Branch this to your comparator, CSV parser, or AAPS database.
 */
data class AdvisorMetrics(
    val periodLabel: String,
    val periodDays: Int,
    
    // Glycemic
    val tir70_180: Double,          // Fraction (0-1)
    val tir70_140: Double,
    val timeBelow70: Double,
    val timeBelow54: Double,
    val timeAbove180: Double,
    val timeAbove250: Double,
    val meanBg: Double,             // mg/dL
    val bgCv: Double,               // Coefficient of variation (%)
    
    // Insulin
    val tdd: Double,                // U/day
    val basalPercent: Double,       // Basal as fraction of TDD
    val smbPercent: Double,         // SMB as fraction of TDD
    val avgBasalRate: Double,       // U/h
    
    // Safety events
    val hypoEvents: Int,
    val severeHypoEvents: Int,
    val hyperEvents: Int,
    
    // Activity context (optional)
    val avgActivityScore: Double? = null,
    val activityDaysDetected: Int = 0
)

/**
 * Domain area for a recommendation.
 */
enum class RecommendationDomain {
    SAFETY,             // Hypo prevention priority
    BASAL,              // Basal rate adjustments
    ISF,                // Insulin Sensitivity Factor
    TARGET,             // BG targets
    SMB,                // SMB settings (max, frequency)
    MODES,              // Meal modes configuration
    ACTIVITY,           // Activity module settings
    PKPD,               // PK/PD model tuning
    PROFILE_QUALITY,    // General profile assessment
    LEARNERS            // Learner module adjustments
}

/**
 * Priority level for recommendations.
 */
enum class RecommendationPriority {
    CRITICAL,   // Safety issue, address immediately
    HIGH,       // Significant improvement opportunity
    MEDIUM,     // Worth considering
    LOW         // Minor optimization
}

/**
 * A single recommendation from the advisor.
 */
data class AimiRecommendation(
    val domain: RecommendationDomain,
    val priority: RecommendationPriority,
    val title: String,
    val description: String,
    val suggestedChanges: List<String> = emptyList(),
    val affectedSettings: List<String> = emptyList()  // Preference keys that would change
)

/**
 * Full advisor report.
 */
data class AdvisorReport(
    val generatedAt: Long,
    val metrics: AdvisorMetrics,
    val overallScore: Double,           // 0-10 score
    val overallAssessment: String,      // "Good", "Needs attention", etc.
    val recommendations: List<AimiRecommendation>,
    val summary: String
)
