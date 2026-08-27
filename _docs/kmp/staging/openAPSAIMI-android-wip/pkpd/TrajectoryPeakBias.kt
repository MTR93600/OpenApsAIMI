package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import kotlin.math.abs

/**
 * Maps trajectory geometry to a **small** peak-time nudge (minutes) for TAP-G.
 *
 * Uses [TrajectoryAnalysis.metrics] only (no re-embedding of peak into historical IOB).
 * Gated: post-bolus window, low COB, bounded magnitude (RFC Phase D — conservative v1).
 *
 * @see docs/research/TAP_G_PEAK_GOVERNOR_RFC.md §Phase D
 */
object TrajectoryPeakBias {

    private const val MAX_ABS_NUDGE_MIN = 4.0

    fun minutesNudge(
        analysis: TrajectoryAnalysis?,
        lastBolusAgeMinutes: Int,
        cobGrams: Double,
    ): Double {
        if (analysis == null) return 0.0
        if (!CleanPostBolusWindow.allowsTrajectoryPeakNudge(lastBolusAgeMinutes, cobGrams)) return 0.0

        val m = analysis.metrics

        // Diverging + low coherence + open: model may be early on peak → slight delay
        if (m.isDiverging && m.coherence < 0.45 && m.openness > 0.55) {
            return MAX_ABS_NUDGE_MIN.coerceAtMost(3.5)
        }

        // Clear convergence without tight spiral: trajectory closing as if insulin peaked slightly early
        if (m.isConverging && m.coherence > 0.62 && !m.isTightSpiral && abs(m.convergenceVelocity) > 0.15) {
            return (-2.5).coerceAtLeast(-MAX_ABS_NUDGE_MIN)
        }

        return 0.0
    }
}
