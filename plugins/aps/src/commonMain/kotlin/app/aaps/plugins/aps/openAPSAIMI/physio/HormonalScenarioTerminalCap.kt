package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.math.min

/**
 * Limits scenario best terminal when hormonal phase would otherwise look like a meal (COB=0).
 */
object HormonalScenarioTerminalCap {

    fun capBestTerminalMgdl(
        bgMgdl: Double,
        bestTerminalMgdl: Double,
        policy: BehavioralRiskPolicy?,
    ): Double {
        val capAbove = policy?.capScenarioBestAboveBgMgdl ?: return bestTerminalMgdl
        if (!policy.suppressMealLikeScenario) return bestTerminalMgdl
        val capped = bgMgdl + capAbove
        return min(bestTerminalMgdl, capped)
    }
}
