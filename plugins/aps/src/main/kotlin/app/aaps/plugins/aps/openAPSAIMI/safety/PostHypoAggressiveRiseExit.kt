package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Product escape from post-hypo protection when a real rise is already past the
 * comfort band: act normally (RBT/HTR/meal SMB) instead of staying in rebound guard.
 *
 * Contract:
 * - Band: [bg] ≥ [targetBg] + [targetMarginMgdl]
 * - Rise: 5‑minute [deltaMgdl5m] > [aggressiveDeltaMgdl]
 *
 * Both limits are user settings. The loop writes them here with [configure] at the start of each run.
 * Standard values: 20 and 5 mg/dL. The old values were 30 and 15.
 */
object PostHypoAggressiveRiseExit {

    const val DEFAULT_TARGET_MARGIN_MGDL = 20.0
    const val DEFAULT_AGGRESSIVE_DELTA_MGDL = 5.0

    const val REASON_CODE = "POST_HYPO_AGGRESSIVE_RISE_EXIT"

    @Volatile
    var targetMarginMgdl: Double = DEFAULT_TARGET_MARGIN_MGDL
        private set

    @Volatile
    var aggressiveDeltaMgdl: Double = DEFAULT_AGGRESSIVE_DELTA_MGDL
        private set

    /** Takes the user settings. A value that is not a number is ignored. */
    fun configure(targetMarginMgdl: Double, aggressiveDeltaMgdl: Double) {
        if (targetMarginMgdl.isFinite()) this.targetMarginMgdl = targetMarginMgdl
        if (aggressiveDeltaMgdl.isFinite()) this.aggressiveDeltaMgdl = aggressiveDeltaMgdl
    }

    fun shouldExit(
        bgMgdl: Double,
        targetBgMgdl: Double,
        deltaMgdl5m: Double,
    ): Boolean {
        if (!bgMgdl.isFinite() || !targetBgMgdl.isFinite() || !deltaMgdl5m.isFinite()) return false
        if (targetBgMgdl <= 0.0) return false
        return bgMgdl >= targetBgMgdl + targetMarginMgdl &&
            deltaMgdl5m > aggressiveDeltaMgdl
    }
}
