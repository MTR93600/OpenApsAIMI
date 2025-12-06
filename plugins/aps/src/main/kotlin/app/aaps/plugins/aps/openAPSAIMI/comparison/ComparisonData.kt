package app.aaps.plugins.aps.openAPSAIMI.comparison

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
    val reasonSmb: String
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
    val variabilityScore: Double, // 0-100, higher = more variable
    val variabilityLabel: String, // "Faible", "Modéré", "Élevé"
    val estimatedHypoRisk: String, // "Faible", "Modéré", "Élevé"
    val aimiVariability: Double,
    val smbVariability: Double
)

data class ClinicalImpact(
    val totalInsulinAimi: Double, // Total insulin in U
    val totalInsulinSmb: Double,
    val cumulativeDiff: Double, // Positive = AIMI more aggressive
    val avgInsulinPerHourAimi: Double,
    val avgInsulinPerHourSmb: Double
    val avgInsulinPerHourAimi: Double,
    val avgInsulinPerHourSmb: Double
)

data class GlycemicMetrics(
    val meanBg: Double = 0.0,
    val medianBg: Double = 0.0,
    val stdDev: Double = 0.0,
    val cv: Double = 0.0,
    val gmi: Double = 0.0,
    val tir70_180: Double = 0.0, // Standard TIR
    val tir70_140: Double = 0.0, // Tight TIR
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
    val preferredAlgorithm: String, // "AIMI", "SMB", "Équivalent"
    val reason: String,
    val confidenceLevel: String, // "Faible", "Modérée", "Élevée"
    val safetyNote: String
)

data class ComparisonTir(
    val actualTir: Double, // % in range (70-180)
    val aimiPredictedTir: Double, // % predicted in range
    val smbPredictedTir: Double // % predicted in range
)

data class FullComparisonReport(
    val stats: ComparisonStats,
    val safety: SafetyMetrics,
    val impact: ClinicalImpact,
    val stats: ComparisonStats,
    val safety: SafetyMetrics,
    val impact: ClinicalImpact,
    val glycemic: GlycemicMetrics, // New Field
    val tir: ComparisonTir,
    val criticalMoments: List<CriticalMoment>,
    val recommendation: Recommendation
)
    val criticalMoments: List<CriticalMoment>,
    val recommendation: Recommendation
)
