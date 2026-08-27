package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskConstants

/**
 * One clamped BG projection path (5-min steps, includes t=0).
 *
 * [pathMinMgdl] / [pathMinHitFloor] reflect the **display** series (after meal-absorption lift).
 * [preLiftPathMinMgdl] / [preLiftPathMinHitFloor] are the gate values for Clamp / DoseTerminal /
 * MealCertainty — computed on the curve **before** meal-absorption terminal flooring.
 */
data class ScenarioProjectionCurve(
    val kind: ScenarioProjectionKind,
    val pointsMgdl: List<Int>,
    val terminalMgdl: Double,
    val pathMinMgdl: Double,
    val pathMinHitFloor: Boolean,
    val preLiftPathMinMgdl: Double = pathMinMgdl,
    val preLiftPathMinHitFloor: Boolean = pathMinHitFloor,
) {
    init {
        require(pointsMgdl.isNotEmpty()) { "Scenario curve must have at least one point" }
    }

    /** Path-min used by dose-facing safety gates. */
    val gatePathMinMgdl: Double get() = preLiftPathMinMgdl

    /** Floor-hit flag used by dose-facing safety gates. */
    val gatePathMinHitFloor: Boolean get() = preLiftPathMinHitFloor

    companion object {
        fun fromRawPoints(
            kind: ScenarioProjectionKind,
            raw: List<Double>,
            preLiftPathMinMgdl: Double? = null,
            preLiftPathMinHitFloor: Boolean? = null,
        ): ScenarioProjectionCurve {
            val clamped = raw.map { point ->
                point.coerceIn(AimiRiskConstants.NUMERIC_FLOOR_MGDL, AimiRiskConstants.NUMERIC_CEILING_MGDL)
            }
            val ints = clamped.map { it.toInt() }
            val rawMin = raw.filter { it.isFinite() }.minOrNull() ?: clamped.first()
            val clampMin = clamped.minOrNull() ?: clamped.first()
            val hitFloor = rawMin < AimiRiskConstants.NUMERIC_FLOOR_MGDL
            return ScenarioProjectionCurve(
                kind = kind,
                pointsMgdl = ints,
                terminalMgdl = clamped.last(),
                pathMinMgdl = clampMin,
                pathMinHitFloor = hitFloor,
                preLiftPathMinMgdl = preLiftPathMinMgdl ?: clampMin,
                preLiftPathMinHitFloor = preLiftPathMinHitFloor ?: hitFloor,
            )
        }
    }
}
