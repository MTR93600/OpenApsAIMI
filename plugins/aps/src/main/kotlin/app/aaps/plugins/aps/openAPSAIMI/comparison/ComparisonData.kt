package app.aaps.plugins.aps.openAPSAIMI.comparison

/**
 * =============================================================================
 * COMPARISON DATA CLASSES
 * =============================================================================
 * 
 * This file contains all data structures for the AIMI vs OpenAPS SMB comparator.
 * 
 * Architecture:
 * - ComparisonInput: Shared input for both engines (BG, IOB, COB, profile)
 * - ComparisonDecision: Output from a single engine run
 * - AlgorithmKpi: Aggregated metrics over a period (TIR, TDD, hypos)
 * - PerformanceScore: Composite score (Safety + Control + Smoothness)
 * - AlgorithmsComparison: Final report comparing both algorithms
 * =============================================================================
 */

// ============================================================================
// ENUMS
// ============================================================================

enum class AlgorithmType {
    AIMI,
    OPENAPS_SMB
}

// ============================================================================
// INPUT DATA
// ============================================================================

/**
 * Shared input for both algorithm engines.
 * Represents a single 5-minute tick with all context needed for decision-making.
 */
data class ComparisonInput(
    val timestamp: Long,
    val bgMgdl: Double,
    val deltaMgdl5min: Double?,
    val shortAvgDelta: Double?,
    val longAvgDelta: Double?,
    val iob: Double,
    val cob: Double,
    val profileBasal: Double,
    val profileSens: Double,
    val profileCarbRatio: Double,
    val targetBg: Double,
    val maxIob: Double,
    val maxBasal: Double,
    val microBolusAllowed: Boolean,
    val autosensRatio: Double = 1.0
)

// ============================================================================
// DECISION OUTPUT
// ============================================================================

/**
 * Output from a single algorithm run.
 * Captures what the algorithm decided to do at a given timestamp.
 */
data class ComparisonDecision(
    val timestamp: Long,
    val algo: AlgorithmType,
    val bgMgdl: Double,
    val smbU: Double,
    val basalRateUph: Double,
    val tempBasalDurationMin: Int,
    val eventualBg: Double?,
    val predictedBg: Double?,
    val iob: Double,
    val cob: Double,
    val reason: String
)

// ============================================================================
// KPI (Key Performance Indicators)
// ============================================================================

/**
 * Aggregated metrics for an algorithm over a specific period.
 * All time values are in percentage of total period.
 * All insulin values are in Units (U).
 */
data class AlgorithmKpi(
    val algo: AlgorithmType,
    val periodStart: Long,
    val periodEnd: Long,
    val durationHours: Double,
    
    // === Glycemic Control ===
    val tir70_180: Double,           // % Time In Range 70-180 mg/dL
    val tir70_140: Double,           // % Time In Tight Range 70-140 mg/dL
    val timeBelow70: Double,         // % Time Below 70 mg/dL (hypo)
    val timeBelow54: Double,         // % Time Below 54 mg/dL (severe hypo)
    val timeAbove180: Double,        // % Time Above 180 mg/dL (hyper)
    val timeAbove250: Double,        // % Time Above 250 mg/dL (severe hyper)
    val meanBg: Double,              // Mean BG in mg/dL
    val medianBg: Double,            // Median BG in mg/dL
    val bgStdDev: Double,            // Standard deviation of BG
    val bgCv: Double,                // Coefficient of Variation (%)
    val gmi: Double,                 // Glucose Management Indicator (estimated A1c)
    
    // === Insulin Delivery ===
    val tdd: Double,                 // Total Daily Dose (U)
    val basalTotal: Double,          // Total basal insulin (U)
    val smbTotal: Double,            // Total SMB insulin (U)
    val smbCount: Int,               // Number of SMBs delivered
    val smbMax: Double,              // Maximum single SMB (U)
    val avgBasalRate: Double,        // Average basal rate (U/h)
    
    // === Loop Behavior ===
    val tbrChangesCount: Int,        // Number of TBR adjustments
    val avgTempBasalPercent: Double, // Average TBR as % of profile
    val zeroBasalMinutes: Double,    // Minutes with basal = 0
    
    // === Safety Events ===
    val hypoEventsCount: Int,        // Number of hypo episodes (<70)
    val severeHypoEventsCount: Int,  // Number of severe hypo episodes (<54)
    val hyperEventsCount: Int        // Number of hyper episodes (>250 for >30min)
)

// ============================================================================
// PERFORMANCE SCORE
// ============================================================================

/**
 * Composite performance score for an algorithm.
 * 
 * Scoring Philosophy:
 * - Safety (50%): Penalizes hypos heavily, especially severe ones
 * - Control (30%): Rewards high TIR, penalizes extended highs
 * - Smoothness (20%): Rewards stable BG with low variability
 * 
 * Each sub-score is 0-10, total score is weighted combination.
 */
data class PerformanceScore(
    val algo: AlgorithmType,
    val safetyScore: Double,         // 0-10, heavily penalized by hypos
    val controlScore: Double,        // 0-10, based on TIR and time >180
    val smoothnessScore: Double,     // 0-10, based on CV and TBR changes
    val totalScore: Double           // Weighted: 50% safety + 30% control + 20% smoothness
) {
    companion object {
        const val WEIGHT_SAFETY = 0.50
        const val WEIGHT_CONTROL = 0.30
        const val WEIGHT_SMOOTHNESS = 0.20
    }
}

// ============================================================================
// COMPARISON REPORT
// ============================================================================

/**
 * Complete comparison report between AIMI and OpenAPS SMB.
 */
data class AlgorithmsComparison(
    val periodLabel: String,         // e.g., "2025-01-01 → 2025-01-07"
    val periodStart: Long,
    val periodEnd: Long,
    val aimiKpi: AlgorithmKpi,
    val openApsSmbKpi: AlgorithmKpi,
    val aimiScore: PerformanceScore,
    val openApsSmbScore: PerformanceScore,
    val winner: AlgorithmType?,      // null if tie
    val summary: String,             // Human-readable summary
    val recommendation: String       // Actionable recommendation
)

// ============================================================================
// LEGACY DATA CLASSES (Kept for CSV compatibility)
// ============================================================================

data class ComparisonEntry(
    val timestamp: Long,
    val date: String,
    val bg: Double,
    val delta: Double?,
    val shortAvgDelta: Double?,
    val longAvgDelta: Double?,
    val iob: Double,
    val cob: Double,
    val aimiRate: Double?,
    val aimiSmb: Double?,
    val aimiDuration: Int,
    val aimiEventualBg: Double?,
    val aimiTargetBg: Double?,
    val smbRate: Double?,
    val smbSmb: Double?,
    val smbDuration: Int,
    val smbEventualBg: Double?,
    val smbTargetBg: Double?,
    val diffRate: Double?,
    val diffSmb: Double?,
    val diffEventualBg: Double?,
    val maxIob: Double?,
    val maxBasal: Double?,
    val microBolusAllowed: Boolean,
    val aimiInsulin30: Double?,
    val smbInsulin30: Double?,
    val cumulativeDiff: Double?,
    val aimiActive: Boolean,
    val smbActive: Boolean,
    val bothActive: Boolean,
    val aimiUamLast: Double?,
    val smbUamLast: Double?,
    val reasonAimi: String,
    val reasonSmb: String,
    // New Interpretation Fields
    val verdict: String = "",
    val artifactFlag: String = "",
    val diffSign: String = ""
)

data class ComparisonStats(
    val totalEntries: Int,
    val avgRateDiff: Double,
    val avgSmbDiff: Double,
    val agreementRate: Double,
    val aimiWinRate: Double,
    val smbWinRate: Double
)

data class SafetyMetrics(
    val variabilityScore: Double,
    val variabilityLabel: String,
    val estimatedHypoRisk: String,
    val aimiVariability: Double,
    val smbVariability: Double
)

data class ClinicalImpact(
    val totalInsulinAimi: Double,
    val totalInsulinSmb: Double,
    val cumulativeDiff: Double,
    val avgInsulinPerHourAimi: Double,
    val avgInsulinPerHourSmb: Double
)

data class GlycemicMetrics(
    val meanBg: Double = 0.0,
    val medianBg: Double = 0.0,
    val stdDev: Double = 0.0,
    val cv: Double = 0.0,
    val gmi: Double = 0.0,
    val tir70_180: Double = 0.0,
    val tir70_140: Double = 0.0,
    val timeBelow70: Double = 0.0,
    val timeBelow54: Double = 0.0,
    val timeAbove180: Double = 0.0,
    val timeAbove250: Double = 0.0
)

data class CriticalMoment(
    val index: Int,
    val timestamp: Long,
    val date: String,
    val bg: Double,
    val iob: Double,
    val cob: Double,
    val divergenceRate: Double?,
    val divergenceSmb: Double?,
    val reasonAimi: String,
    val reasonSmb: String
)

data class Recommendation(
    val preferredAlgorithm: String,
    val reason: String,
    val confidenceLevel: String,
    val safetyNote: String
)

data class ComparisonTir(
    val actualTir: Double,
    val aimiPredictedTir: Double,
    val smbPredictedTir: Double
)

data class FullComparisonReport(
    val stats: ComparisonStats,
    val safety: SafetyMetrics,
    val impact: ClinicalImpact,
    val glycemic: GlycemicMetrics,
    val tir: ComparisonTir,
    val criticalMoments: List<CriticalMoment>,
    val recommendation: Recommendation
)
