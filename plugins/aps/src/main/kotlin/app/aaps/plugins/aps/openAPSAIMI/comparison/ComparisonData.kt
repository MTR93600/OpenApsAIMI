package app.aaps.plugins.aps.openAPSAIMI.comparison

data class ComparisonEntry(
    val timestamp: Long,
    val date: String,
    val bg: Double,
    val iob: Double,
    val cob: Double,
    val aimiRate: Double?,
    val aimiSmb: Double?,
    val aimiDuration: Int,
    val smbRate: Double?,
    val smbSmb: Double?,
    val smbDuration: Int,
    val diffRate: Double?,
    val diffSmb: Double?,
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
