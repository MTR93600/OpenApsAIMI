package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.abs

/**
 * Discrete channel interference resolution — §6.4.
 */
object ChannelInterferenceOptimizer {

    data class Result(
        val smbU: Double,
        val tbrFraction: Double,
        val cost: Double,
    )

    private val TBR_CANDIDATES = doubleArrayOf(0.7, 1.0, 1.3)

    fun optimize(
        bgMgdl: Double,
        targetBgMgdl: Double,
        smbDemandU: Double,
        tbrDemandFraction: Double,
        tensionSum: Double,
        maxSmbU: Double,
    ): Result {
        val smbOptions = buildSmbCandidates(smbDemandU, maxSmbU)
        var best = Result(smbDemandU, tbrDemandFraction, Double.MAX_VALUE)
        for (smb in smbOptions) {
            for (tbr in TBR_CANDIDATES) {
                val cost = channelCost(bgMgdl, targetBgMgdl, smb, tbr, tensionSum)
                if (cost < best.cost) {
                    best = Result(smb, tbr, cost)
                }
            }
        }
        return best
    }

    private fun buildSmbCandidates(smbDemandU: Double, maxSmbU: Double): DoubleArray {
        val maxCand = maxSmbU.coerceAtLeast(smbDemandU).coerceAtLeast(2.0)
        return listOf(0.0, 0.5, 1.0, 2.0, maxCand).distinct().sorted().toDoubleArray()
    }

    private fun channelCost(
        bg: Double,
        target: Double,
        smbU: Double,
        tbrFraction: Double,
        tensionSum: Double,
    ): Double {
        val deviation = abs(bg - target)
        val interference = smbU * (1.0 - tbrFraction.coerceIn(0.0, 1.5))
        return 0.02 * deviation * deviation + 0.5 * interference * interference + 0.3 * tensionSum
    }
}
