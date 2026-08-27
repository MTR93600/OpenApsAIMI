package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import kotlin.math.abs
import kotlin.math.max

/**
 * Filters incoherent hypo signals when BG is in a hyper band (Phase 0 / HTR).
 */
object HyperTrajectoryHypoCredibility {

    fun highBgBandMgdl(targetBgMgdl: Double, highBgPreferenceMgdl: Double): Double =
        (highBgPreferenceMgdl - targetBgMgdl).coerceAtLeast(30.0)

    fun hypoCredibilityDropMgdl(devAboveTargetMgdl: Double): Double =
        35.0 + 0.1 * devAboveTargetMgdl.coerceAtLeast(0.0)

    /**
     * Wave2 H2: numeric-floor path-min on a high flat BG is not a credible hypo forecast
     * (same artefact family as [DoseTerminalSnapshot] plateau lift).
     */
    fun isNumericFloorArtefactOnPlateau(
        bgMgdl: Double,
        minPredictedBgMgdl: Double?,
        deltaMgdlPer5: Double?,
    ): Boolean {
        if (minPredictedBgMgdl == null || !minPredictedBgMgdl.isFinite()) return false
        if (!bgMgdl.isFinite() || bgMgdl < DoseTerminalSnapshot.PLATEAU_BG_MGDL) return false
        if (minPredictedBgMgdl > DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL) return false
        val delta = deltaMgdlPer5?.takeIf { it.isFinite() } ?: return false
        if (abs(delta) > DoseTerminalSnapshot.PLATEAU_FLAT_DELTA_ABS_MGDL) return false
        if (delta <= ClampPkpdScenarioReconcile.MAX_NEG_DELTA_MGDL) return false
        return true
    }

    fun isMinPredictedCredible(
        bgMgdl: Double,
        minPredictedBgMgdl: Double?,
        targetBgMgdl: Double,
        highBgPreferenceMgdl: Double,
        tier: HyperSeverityTier = HyperSeverityTier.OFF,
        deltaMgdlPer5: Double? = null,
    ): Boolean {
        if (minPredictedBgMgdl == null || !minPredictedBgMgdl.isFinite()) return true
        if (isNumericFloorArtefactOnPlateau(bgMgdl, minPredictedBgMgdl, deltaMgdlPer5)) return false
        val dev = bgMgdl - targetBgMgdl
        val band = highBgBandMgdl(targetBgMgdl, highBgPreferenceMgdl)
        if (dev < band * 0.75 && tier < HyperSeverityTier.EMERGING) return true
        if (tier < HyperSeverityTier.EMERGING && dev < band * 0.85) return true
        return minPredictedBgMgdl >= bgMgdl - hypoCredibilityDropMgdl(dev)
    }

    /**
     * Uplifts pathological low terminals used by hypo guard when hyper trajectory is credible.
     */
    fun sanitizeTerminalsForHypoGuard(
        bgMgdl: Double,
        predictedBgMgdl: Double,
        eventualBgMgdl: Double,
        minPredictedBgMgdl: Double?,
        targetBgMgdl: Double,
        highBgPreferenceMgdl: Double,
        scenarioBestTerminalMgdl: Double?,
        deltaMgdlPer5: Double? = null,
    ): Pair<Double, Double> {
        val floorArtefact = isNumericFloorArtefactOnPlateau(bgMgdl, minPredictedBgMgdl, deltaMgdlPer5)
        val dev = bgMgdl - targetBgMgdl
        val band = highBgBandMgdl(targetBgMgdl, highBgPreferenceMgdl)
        if (!floorArtefact && dev < band * 0.75) {
            return predictedBgMgdl to eventualBgMgdl
        }
        val floorTerminal = if (floorArtefact) {
            ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL
        } else {
            bgMgdl - hypoCredibilityDropMgdl(dev)
        }
        var predicted = predictedBgMgdl
        var eventual = eventualBgMgdl
        if (!isMinPredictedCredible(
                bgMgdl = bgMgdl,
                minPredictedBgMgdl = minPredictedBgMgdl,
                targetBgMgdl = targetBgMgdl,
                highBgPreferenceMgdl = highBgPreferenceMgdl,
                deltaMgdlPer5 = deltaMgdlPer5,
            )
        ) {
            predicted = max(predicted, floorTerminal)
            eventual = max(eventual, floorTerminal)
        }
        val best = scenarioBestTerminalMgdl?.takeIf { it.isFinite() }
        if (best != null && best > bgMgdl + 40.0) {
            if (eventual < floorTerminal) {
                eventual = max(eventual, minOf(best - 60.0, bgMgdl - 15.0))
            }
            if (predicted < floorTerminal) {
                predicted = max(predicted, minOf(best - 80.0, bgMgdl - 20.0))
            }
        }
        return predicted to eventual
    }
}
