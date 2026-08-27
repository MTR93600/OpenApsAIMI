package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max
import kotlin.math.min

/**
 * Bounds UAM/scenario terminal projections during counterregulatory rebound.
 * Linear momentum overshoot (eventual 300+ while actual peak ~130) must not drive MPC hyper-correction.
 */
object PostHypoProjectionCap {

    const val LOG_PREFIX = "POST_HYPO_PROJECTION_CAP"

    data class Result(
        val cappedTerminalMgdl: Double,
        val wasCapped: Boolean,
        val ceilingMgdl: Double,
    )

    fun capTerminalMgdl(
        bgMgdl: Double,
        targetBgMgdl: Double,
        deltaMgdl5m: Double,
        terminalMgdl: Double,
        minBgLookback75m: Double,
        hasIndependentMealEvidence: Boolean,
    ): Result {
        if (!terminalMgdl.isFinite()) {
            return Result(terminalMgdl, wasCapped = false, ceilingMgdl = terminalMgdl)
        }
        if (hasIndependentMealEvidence) {
            return Result(terminalMgdl, wasCapped = false, ceilingMgdl = terminalMgdl)
        }
        if (minBgLookback75m >= CorrectionAggressionGate.REBOUND_MIN_BG_LOOKBACK_MGDL) {
            return Result(terminalMgdl, wasCapped = false, ceilingMgdl = terminalMgdl)
        }
        val reboundBudgetMgdl = max(25.0, deltaMgdl5m * 12.0).coerceAtMost(60.0)
        val tierCeiling = targetBgMgdl + CorrectionAggressionGate.REBOUND_BG_MARGIN_MGDL + 15.0
        val ceiling = min(bgMgdl + reboundBudgetMgdl, tierCeiling)
        val floorMgdl = bgMgdl + 5.0
        if (ceiling < floorMgdl) {
            // Rebound already above post-hypo tier — skip cap (empty coerceIn range would abort tick).
            return Result(terminalMgdl, wasCapped = false, ceilingMgdl = ceiling)
        }
        val capped = terminalMgdl.coerceIn(floorMgdl, ceiling)
        return Result(
            cappedTerminalMgdl = capped,
            wasCapped = capped < terminalMgdl - 1e-6,
            ceilingMgdl = ceiling,
        )
    }
}
