package app.aaps.plugins.aps.openAPSAIMI.release

/**
 * Output of [HyperTrajectoryReleaseEvaluator] for one loop tick.
 */
data class HyperTrajectoryReleaseResult(
    val active: Boolean,
    val tier: HyperSeverityTier,
    val severityWeight: Double,
    val smbFloorU: Double,
    val v3SmbBeforeU: Double,
    val v3SmbAfterU: Double,
    val absorptionOffsetMgdl: Double,
    val suppressTrajBasalShift: Boolean,
    val hypoMinPredIgnored: Boolean,
    val reason: String,
)
