package app.aaps.plugins.aps.openAPSAIMI.advisor

/**
 * =============================================================================
 * AIMI ADVISOR DATA MODELS
 * =============================================================================
 * 
 * Simple, non-nullable data classes for the AIMI Profile Advisor feature.
 * Zero external dependencies - guaranteed crash-free.
 * =============================================================================
 */

/**
 * Metrics collected for analysis.
 */
data class AdvisorMetrics(
    val periodLabel: String,
    val tir70_180: Double,          // Fraction (0-1)
    val tir70_140: Double,
    val timeBelow70: Double,
    val timeBelow54: Double,
    val timeAbove180: Double,
    val timeAbove250: Double,
    val meanBg: Double,             // mg/dL
    val tdd: Double,                // U/day
    val basalPercent: Double,       // Basal as fraction of TDD
    val hypoEvents: Int,
    val severeHypoEvents: Int,
    val hyperEvents: Int
)

/**
 * Domain area for a recommendation.
 */
enum class RecommendationDomain {
    SAFETY,             // Hypo prevention priority
    BASAL,              // Basal rate adjustments
    ISF,                // Insulin Sensitivity Factor
    TARGET,             // BG targets
    SMB,                // SMB settings
    MODES,              // Meal modes configuration
    PROFILE_QUALITY     // General profile assessment
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
    val suggestedChanges: List<String> = emptyList()
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
