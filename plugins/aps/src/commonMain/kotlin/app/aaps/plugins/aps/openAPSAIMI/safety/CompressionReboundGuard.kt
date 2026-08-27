package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Compression / impossible-rise guard extracted for tests and single responsibility.
 * Behaviour matches legacy `DetermineBasalAIMI2.isCompressionProtectionCondition` threshold.
 */
object CompressionReboundGuard {

    internal const val DELTA_THRESHOLD_MGDL_PER_5MIN = 35f

    fun isImpossibleRise(deltaMgDlPer5min: Float): Boolean =
        deltaMgDlPer5min > DELTA_THRESHOLD_MGDL_PER_5MIN

    fun reasonLine(): String =
        "🛡️ Safety Net: Compression Rebound Block (Delta > 35) -> Autodrive OFF\n"
}
