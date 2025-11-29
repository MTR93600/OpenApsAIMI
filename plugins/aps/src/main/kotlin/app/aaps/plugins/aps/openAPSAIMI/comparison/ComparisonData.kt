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
