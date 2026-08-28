package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType

/**
 * Tick-local context for scenario fusion — no Android dependencies.
 */
data class ScenarioProjectionContext(
    val mealContext: MealSafetyContext = MealSafetyContext(),
    val effectiveCobG: Double = 0.0,
    val targetBgMgdl: Double = 100.0,
    val trajectoryAnalysis: TrajectoryAnalysis? = null,
    val trajectoryRelevanceScore: Double = 0.0,
    val activityProtectionMode: Boolean = false,
    val contextActivityActive: Boolean = false,
    val contextSmbFactor: Float = 1.0f,
    val physioSmbFactor: Double = 1.0,
    val physioReactivityFactor: Double = 1.0,
    val physioBasalFactor: Double = 1.0,
    val physiologicalPhase: PhysiologicalPhase = PhysiologicalPhase.OFF,
    val suppressMealLikeUam: Boolean = false,
    val scenarioBestCapAboveBgMgdl: Double? = null,
    val mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
    val mealAbsorptionMemoryActive: Boolean = false,
    val mealAbsorptionBestTFloorAboveBgMgdl: Double? = null,
) {
    val mealIntent: Boolean get() = mealContext.hasMealIntent

    val trajectoryType: TrajectoryType?
        get() = trajectoryAnalysis?.classification

    val trajectoryModulationActive: Boolean
        get() = trajectoryAnalysis != null &&
            trajectoryRelevanceScore > 0.4 &&
            (trajectoryAnalysis.modulation.isSignificant())
}
