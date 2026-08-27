package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves

data class ScenarioProjectionInput(
    val bgNowMgdl: Double,
    val deltaMgdlPer5: Float,
    val curves: AdvancedPredictionCurves,
    val context: ScenarioProjectionContext,
)

data class ScenarioProjectionPair(
    val clinicalFloor: ScenarioProjectionCurve,
    val scenarioBest: ScenarioProjectionCurve,
    val contributors: List<ScenarioContributor>,
    val cobPointsMgdl: List<Int>,
    val ztPointsMgdl: List<Int>,
    val trajectoryType: String? = null,
) {
    fun formatLogLine(): String {
        val contrib = contributors.joinToString(",") { it.id.name }
        val bestGate = scenarioBest.gatePathMinMgdl.toInt()
        val bestDisplay = scenarioBest.pathMinMgdl.toInt()
        val gateSuffix =
            if (bestGate != bestDisplay) " bestGateMin=$bestGate" else ""
        return "SCENARIO: floorT=${clinicalFloor.terminalMgdl.toInt()} bestT=${scenarioBest.terminalMgdl.toInt()} " +
            "floorMin=${clinicalFloor.pathMinMgdl.toInt()} bestMin=$bestDisplay$gateSuffix " +
            "gap=${(scenarioBest.terminalMgdl - clinicalFloor.terminalMgdl).toInt()} " +
            "contrib=[$contrib]"
    }
}
