package app.aaps.plugins.aps.openAPSAIMI.release

import kotlin.math.max

/**
 * Phase 4: maps HTR classification into Autodrive MPC inputs (Ra floor, tier ordinal).
 */
object HyperTrajectoryMpcFeedForward {

    data class MpcHints(
        val tierOrdinal: Int,
        val projectedDevMgdl: Double,
        val projectionLeadMgdl: Double,
        val estimatedRaFloorMgdlPerMin: Double,
    )

    fun hintsFromClassification(
        classification: HyperSeverityClassifier.Output,
        bgMgdl: Double,
        bestTerminalMgdl: Double,
        isNight: Boolean,
        exerciseLockout: Boolean,
    ): MpcHints {
        if (isNight || exerciseLockout || !classification.tier.isReleaseEligible) {
            return MpcHints(
                tierOrdinal = HyperSeverityTier.OFF.ordinal,
                projectedDevMgdl = 0.0,
                projectionLeadMgdl = 0.0,
                estimatedRaFloorMgdlPerMin = 0.0,
            )
        }
        val lead = max(0.0, bestTerminalMgdl - bgMgdl)
        val raFloor = when (classification.tier) {
            HyperSeverityTier.ANTICIPATORY -> (0.85 + lead / 280.0).coerceIn(0.85, 2.2)
            HyperSeverityTier.EMERGING -> 1.05
            HyperSeverityTier.ESTABLISHED -> 1.25
            HyperSeverityTier.DEEP -> if (classification.riseActive) 1.1 else 0.75
            HyperSeverityTier.OFF -> 0.0
        }
        return MpcHints(
            tierOrdinal = classification.tier.ordinal,
            projectedDevMgdl = classification.projectedDevMgdl,
            projectionLeadMgdl = lead,
            estimatedRaFloorMgdlPerMin = raFloor,
        )
    }

    fun blendEstimatedRa(baseRa: Double, hints: MpcHints): Double {
        if (hints.estimatedRaFloorMgdlPerMin <= 0.0) return baseRa
        return max(baseRa, hints.estimatedRaFloorMgdlPerMin)
    }

    fun aggressiveMaxSmbMultiplier(tier: HyperSeverityTier, projectionLeadMgdl: Double): Double {
        if (!tier.isReleaseEligible) return 1.0
        val leadBoost = (1.0 + projectionLeadMgdl / 400.0).coerceIn(1.0, 1.25)
        return when (tier) {
            HyperSeverityTier.ANTICIPATORY -> 1.15 * leadBoost
            HyperSeverityTier.EMERGING -> 1.10 * leadBoost
            HyperSeverityTier.ESTABLISHED -> 1.05
            HyperSeverityTier.DEEP -> 1.0
            HyperSeverityTier.OFF -> 1.0
        }.coerceAtMost(1.35)
    }
}
