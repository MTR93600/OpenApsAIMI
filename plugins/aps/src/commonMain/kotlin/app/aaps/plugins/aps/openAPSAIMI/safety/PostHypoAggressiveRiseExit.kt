package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Product escape from post-hypo protection when an aggressive rise is already past the
 * comfort band: act normally (RBT/HTR/meal SMB) instead of staying in rebound guard.
 *
 * Contract (2026-07-17):
 * - Band: [bg] ≥ [targetBg] + [TARGET_MARGIN_MGDL]
 * - Aggressive: 5‑minute [deltaMgdl5m] > [AGGRESSIVE_DELTA_MGDL]
 */
object PostHypoAggressiveRiseExit {

    const val TARGET_MARGIN_MGDL = 30.0
    const val AGGRESSIVE_DELTA_MGDL = 15.0

    const val REASON_CODE = "POST_HYPO_AGGRESSIVE_RISE_EXIT"

    fun shouldExit(
        bgMgdl: Double,
        targetBgMgdl: Double,
        deltaMgdl5m: Double,
    ): Boolean {
        if (!bgMgdl.isFinite() || !targetBgMgdl.isFinite() || !deltaMgdl5m.isFinite()) return false
        if (targetBgMgdl <= 0.0) return false
        return bgMgdl >= targetBgMgdl + TARGET_MARGIN_MGDL &&
            deltaMgdl5m > AGGRESSIVE_DELTA_MGDL
    }
}
