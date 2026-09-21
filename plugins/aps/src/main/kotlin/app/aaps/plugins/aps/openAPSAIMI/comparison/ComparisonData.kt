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
    val profileBasalUph: Double,
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
    val totalScore: Double,          // Weighted by scoring mode
    val mode: ScoringMode = ScoringMode.BALANCED
) {
    companion object {
        const val WEIGHT_SAFETY = 0.50
        const val WEIGHT_CONTROL = 0.30
        const val WEIGHT_SMOOTHNESS = 0.20
    }
}

enum class ScoringMode {
    BALANCED,
    POSTPRANDIAL,
    OVERNIGHT
}

/**
 * Why the two algorithms did not decide the same thing at one tick.
 *
 * Each value maps to one flag column of `comparison_aimi_smb.csv`. The values carry no text on
 * purpose: the labels are shown to the user, so they must come from string resources of the module
 * that displays them.
 */
enum class DivergenceCause {

    /** AIMI was in its meal priority context. */
    AIMI_MEAL_PRIORITY,

    /** AIMI was inside its refractory window. */
    AIMI_REFRACTORY,

    /** AIMI throttled the dose from the pkpd model. */
    AIMI_THROTTLE,

    /** The AIMI barrier function (CBF) limited the dose. */
    AIMI_CBF,

    /** The reference SMB algorithm was inside its bolus interval. */
    SMB_REFRACTORY,

    /** The reference SMB algorithm throttled the dose. */
    SMB_THROTTLE,

    /** The barrier function (CBF) limited the reference SMB dose. */
    SMB_CBF,

    /** Glucose was rising like after a meal. */
    CONTEXT_MEAL_RISE,

    /** Carbs were still on board. */
    CONTEXT_COB_ACTIVE,

    /** At least one algorithm read the rise as an undeclared meal (UAM). */
    CONTEXT_UAM_BIAS
}

/**
 * A coarse three-level rating shared by several comparator metrics: BG variability, estimated hypo
 * risk, and recommendation confidence. The values carry no text on purpose, same as
 * [DivergenceCause]: the labels are shown to the user, so they must come from string resources of the
 * module that displays them.
 *
 * One French word has more than one spelling depending on the grammatical gender of the noun it
 * describes (for example "modéré" for a score, "modérée" for a confidence), so the UI module keeps
 * more than one set of string resources for this same enum instead of a single shared one.
 */
enum class ComparisonLevel {
    LOW,
    MODERATE,
    HIGH
}

/**
 * The fixed safety note attached to a [Recommendation]. Carries no text, see [DivergenceCause].
 */
enum class SafetyNoteKind {

    /** Estimated hypo risk is high. */
    INCREASED_MONITORING_RECOMMENDED,

    /** BG variability is high. */
    SIGNIFICANT_VARIABILITY_DETECTED,

    /** The two algorithms deliver very different total insulin. */
    LARGE_INSULIN_DIFFERENCE,

    /** None of the above triggered. */
    ACCEPTABLE_SAFETY_PROFILE
}

/**
 * Why one algorithm (or neither) was recommended. Carries no text, see [DivergenceCause].
 *
 * A few kinds need a number or a [ComparisonLevel] to fill their string resource template. Those are
 * carried as separate, optional fields on [Recommendation] instead of inside this enum, so this stays
 * a plain enum:
 *  - [SMB_MORE_AGGRESSIVE_WITH_VARIABILITY] needs [Recommendation.reasonAggressivenessRatio] and
 *    [Recommendation.reasonVariability].
 *  - [MORE_CONSERVATIVE_WITH_VARIABILITY] needs [Recommendation.reasonVariability].
 *  - [SIMILAR_PERFORMANCE] needs [Recommendation.reasonAgreementRate].
 *  - [MORE_REACTIVE_TO_GLUCOSE_CHANGES] needs no extra data.
 */
enum class RecommendationReasonKind {
    SMB_MORE_AGGRESSIVE_WITH_VARIABILITY,
    MORE_CONSERVATIVE_WITH_VARIABILITY,
    MORE_REACTIVE_TO_GLUCOSE_CHANGES,
    SIMILAR_PERFORMANCE
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
    val diffSign: String = "",
    // Cause flags. Only rows of the current layout carry them, older rows keep the defaults.
    val aimiFlagMealPriority: Boolean = false,
    val aimiFlagRefractory: Boolean = false,
    val aimiFlagThrottle: Boolean = false,
    val aimiFlagCbf: Boolean = false,
    val smbFlagRefractory: Boolean = false,
    val smbFlagThrottle: Boolean = false,
    val smbFlagCbf: Boolean = false,
    val contextMealRise: Boolean = false,
    val contextCobActive: Boolean = false,
    val contextUamBias: Boolean = false,
    /** Minutes since the last bolus seen by the reference SMB algorithm, null when not written. */
    val smbLastBolusAgeMin: Double? = null
) {

    /** The causes that are set on this row, in reading order. */
    fun causes(): List<DivergenceCause> = buildList {
        if (aimiFlagMealPriority) add(DivergenceCause.AIMI_MEAL_PRIORITY)
        if (aimiFlagRefractory) add(DivergenceCause.AIMI_REFRACTORY)
        if (aimiFlagThrottle) add(DivergenceCause.AIMI_THROTTLE)
        if (aimiFlagCbf) add(DivergenceCause.AIMI_CBF)
        if (smbFlagRefractory) add(DivergenceCause.SMB_REFRACTORY)
        if (smbFlagThrottle) add(DivergenceCause.SMB_THROTTLE)
        if (smbFlagCbf) add(DivergenceCause.SMB_CBF)
        if (contextMealRise) add(DivergenceCause.CONTEXT_MEAL_RISE)
        if (contextCobActive) add(DivergenceCause.CONTEXT_COB_ACTIVE)
        if (contextUamBias) add(DivergenceCause.CONTEXT_UAM_BIAS)
    }
}

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
    val variabilityLevel: ComparisonLevel,
    val estimatedHypoRisk: ComparisonLevel,
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
    val reasonSmb: String,
    /** Verdict of the row, so the screen does not have to look the entry up again. */
    val verdict: String = "",
    /** Artifact flag of the row, for example `SCREAMING_SHADOW` or `VALID`. */
    val artifactFlag: String = "",
    /** Why the two algorithms diverged here. Empty for rows written before the flag columns. */
    val causes: List<DivergenceCause> = emptyList()
)

/**
 * @param preferredAlgorithm `null` means the two algorithms are equivalent, the same convention
 *   [AlgorithmsComparison.winner] already uses.
 * @param reasonVariability set only when [reasonKind] is [RecommendationReasonKind.SMB_MORE_AGGRESSIVE_WITH_VARIABILITY]
 *   or [RecommendationReasonKind.MORE_CONSERVATIVE_WITH_VARIABILITY].
 * @param reasonAggressivenessRatio set only when [reasonKind] is
 *   [RecommendationReasonKind.SMB_MORE_AGGRESSIVE_WITH_VARIABILITY].
 * @param reasonAgreementRate set only when [reasonKind] is [RecommendationReasonKind.SIMILAR_PERFORMANCE].
 */
data class Recommendation(
    val preferredAlgorithm: AlgorithmType?,
    val reasonKind: RecommendationReasonKind,
    val reasonVariability: ComparisonLevel? = null,
    val reasonAggressivenessRatio: Double? = null,
    val reasonAgreementRate: Double? = null,
    val confidenceLevel: ComparisonLevel,
    val safetyNote: SafetyNoteKind
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
