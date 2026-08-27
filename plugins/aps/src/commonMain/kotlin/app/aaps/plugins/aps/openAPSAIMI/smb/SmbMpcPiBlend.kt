package app.aaps.plugins.aps.openAPSAIMI.smb

import kotlin.math.max

/**
 * Pure MPC/PI blend math for [SmbInstructionExecutor].
 * Extracted for unit tests and long-term maintainability.
 */
internal object SmbMpcPiBlend {

    const val FAST_RISE_DELTA_THRESHOLD = 4.0
    const val POST_HYPO_BG_THRESHOLD_MGDL = 70.0
    const val FAST_RISE_BG_SCORE_FLOOR = 0.2

    /**
     * Drives blend weight: [alphaRaw] = 0.5 + 0.5 * deltaScore (then clamped to 0.3..0.9).
     */
    fun computeDeltaScore(
        delta: Double,
        bg: Double,
        targetBg: Double,
        postHypoRecent: Boolean,
    ): Double {
        val bgScore = ((bg - targetBg) / 100.0).coerceIn(0.0, 1.0)
        return when {
            delta <= FAST_RISE_DELTA_THRESHOLD -> bgScore
            postHypoRecent -> 0.0
            else -> bgScore.coerceAtLeast(FAST_RISE_BG_SCORE_FLOOR)
        }
    }

    fun computeAlpha(deltaScore: Double): Double {
        return (0.5 + 0.5 * deltaScore).coerceIn(0.3, 0.9)
    }

    /**
     * Missing activity demand (U) before Weibull back-transform. Never negative —
     * negative values are not deliverable insulin and poison the PI blend leg.
     */
    fun computeActMissing(
        delta: Double,
        actCurr: Double,
        actFuture: Double,
        smbToGive: Double,
        actTarget: Double,
    ): Double {
        val raw = if (delta <= FAST_RISE_DELTA_THRESHOLD) {
            (actCurr * smbToGive - max(actFuture, 0.0)) / 5.0
        } else {
            (actTarget - max(actFuture, 0.0)) / 5.0
        }
        return raw.coerceAtLeast(0.0)
    }

    fun blendMpcPi(
        optimalBasalMpc: Double,
        piDose: Double,
        alpha: Double,
    ): Double {
        val mpc = optimalBasalMpc.coerceAtLeast(0.0)
        val pi = piDose.coerceAtLeast(0.0)
        return alpha * mpc + (1.0 - alpha) * pi
    }
}
