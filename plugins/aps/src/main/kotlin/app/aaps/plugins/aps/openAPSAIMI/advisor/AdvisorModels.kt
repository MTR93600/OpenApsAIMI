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
    val gmi: Double,                // % (derived)
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
 * Severity classification for the overall score.
 */
enum class AdvisorSeverity {
    GOOD,       // Score >= 7.0
    WARNING,    // 4.0 <= Score < 7.0
    CRITICAL    // Score < 4.0
}

/**
 * A single recommendation from the advisor.
 * Uses Resource IDs for localization.
 */
data class AimiRecommendation(
    val domain: RecommendationDomain,
    val priority: RecommendationPriority,
    val titleResId: Int,
    val descriptionResId: Int,
    val actionsResIds: List<Int> = emptyList(),
    // New: Specific actions for the Rules Engine
    val advisorActions: List<AdvisorAction> = emptyList()
)

/**
 * Full advisor report.
 */
data class AdvisorReport(
    val generatedAt: Long,
    val metrics: AdvisorMetrics,
    val overallScore: Double,           // 0-10 score
    val overallSeverity: AdvisorSeverity,
    val overallAssessment: String,
    val recommendations: List<AimiRecommendation>,
    val summary: String
)

/**
 * =============================================================================
 * RULES ENGINE MODELS
 * =============================================================================
 */

data class AdvisorContext(
    val metrics: AdvisorMetrics,
    val profile: AimiProfileSnapshot,
    val prefs: AimiPrefsSnapshot
)

data class AimiProfileSnapshot(
    val nightBasal: Double,      // U/h (average or specific block)
    val icRatio: Double,         // g/U
    val isf: Double,             // mg/dL/U
    val targetBg: Double         // mg/dL
)

data class AimiPrefsSnapshot(
    val maxSmb: Double,                     // U
    val lunchFactor: Double,                // multiplier
    val unifiedReactivityFactor: Double,    // multiplier
    val autodriveMaxBasal: Double           // U/h
)

data class AdvisorFlag(
    val code: String,
    val severity: AdvisorSeverity
)

data class AdvisorAction(
    val actionCode: AdvisorActionCode,
    val params: Map<String, Any> = emptyMap()
)

enum class AdvisorActionCode {
    INCREASE_NIGHT_BASAL,
    REDUCE_MAX_SMB,
    INCREASE_LUNCH_FACTOR
}
