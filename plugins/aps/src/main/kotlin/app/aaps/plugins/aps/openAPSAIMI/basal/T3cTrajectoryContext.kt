package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import kotlin.math.min

/**
 * Read-only snapshot for T3c brittle mode: ties advanced predictions and phase-space
 * trajectory to basal braking without entering the main AIMI / Autodrive pipeline.
 */
data class T3cTrajectoryContext(
    val minPredBg: Double,
    val eventualPredBg: Double,
    val lgsThresholdMgdl: Double,
    val trajectoryAnalysisActive: Boolean,
    val convergenceVelocity: Double?,
    val energyBalance: Double?,
    val trajectoryTypeName: String?
) {
    companion object {
        fun build(
            minPredBg: Double,
            eventualPredBg: Double,
            bg: Double,
            lgsThresholdMgdl: Double,
            trajectoryEnabled: Boolean,
            lastAnalysis: TrajectoryAnalysis?
        ): T3cTrajectoryContext {
            val minP = (if (minPredBg.isFinite()) minPredBg else bg).coerceIn(39.0, 401.0)
            val ev = (if (eventualPredBg.isFinite()) eventualPredBg else bg).coerceIn(39.0, 401.0)
            val la = lastAnalysis
            return T3cTrajectoryContext(
                minPredBg = minP,
                eventualPredBg = ev,
                lgsThresholdMgdl = lgsThresholdMgdl.coerceIn(65.0, 100.0),
                trajectoryAnalysisActive = trajectoryEnabled,
                convergenceVelocity = la?.metrics?.convergenceVelocity,
                energyBalance = la?.metrics?.energyBalance,
                trajectoryTypeName = la?.classification?.name
            )
        }

        /** Lowest credible BG among prediction curve and eventual BG. */
        fun guardBg(ctx: T3cTrajectoryContext): Double =
            min(ctx.minPredBg, ctx.eventualPredBg)
    }
}
