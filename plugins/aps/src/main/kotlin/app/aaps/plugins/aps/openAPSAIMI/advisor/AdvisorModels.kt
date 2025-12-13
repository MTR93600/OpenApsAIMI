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
    PKPD,               // Adaptive PK/PD settings
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
 * Smart Actions
 */
sealed class AdvisorAction {
    // A single atomic change
    data class Prediction(
        val key: Any,        // PreferenceKey object
        val keyName: String, // User friendly name or resource ID
        val oldValue: Any,
        val newValue: Any,
        val explanation: String
    )

    // Action can now be a single update or a batch
    data class UpdatePreference(
        val changes: List<Prediction>
    ) : AdvisorAction()
}

/**
 * A single recommendation from the advisor.
 * Uses Resource IDs for localization.
 */
data class AimiRecommendation(
    val titleResId: Int,
    val descriptionResId: Int,
    val priority: RecommendationPriority,
    val domain: RecommendationDomain,
    val action: AdvisorAction? = null,
    val extraData: String? = null // For dynamic description formatting
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
    // PkpdSuggestions merged into recommendations with Domain.PKPD
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
    val prefs: AimiPrefsSnapshot,
    val pkpdPrefs: PkpdPrefsSnapshot
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

/**
 * =============================================================================
 * PKPD MODELS
 * =============================================================================
 */

data class PkpdPrefsSnapshot(
    val pkpdEnabled: Boolean,
    val initialDiaH: Double,
    val initialPeakMin: Double,
    val boundsDiaMinH: Double,
    val boundsDiaMaxH: Double,
    val boundsPeakMinMin: Double,
    val boundsPeakMinMax: Double,
    val maxDiaChangePerDayH: Double,
    val maxPeakChangePerDayMin: Double,
    val isfFusionMinFactor: Double,
    val isfFusionMaxFactor: Double,
    val isfFusionMaxChangePerTick: Double,
    val smbTailThreshold: Double,
    val smbTailDamping: Double,
    val smbExerciseDamping: Double,
    val smbLateFatDamping: Double
)
