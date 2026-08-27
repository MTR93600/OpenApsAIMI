package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.core.interfaces.aps.Predictions

data class PredictionPathBounds(
    val pathMinRawMgdl: Double?,
    val pathMinClampedMgdl: Double?,
    val pathMinHitNumericFloor: Boolean,
)

internal object PredictionPathMath {

    fun boundsFromRawSeries(rawPoints: List<Double>): PredictionPathBounds {
        val finite = rawPoints.filter { it.isFinite() }
        if (finite.isEmpty()) {
            return PredictionPathBounds(null, null, false)
        }
        val rawMin = finite.min()
        val clampedMin = finite.minOf { point ->
            point.coerceIn(AimiRiskConstants.NUMERIC_FLOOR_MGDL, AimiRiskConstants.NUMERIC_CEILING_MGDL)
        }
        return PredictionPathBounds(
            pathMinRawMgdl = rawMin,
            pathMinClampedMgdl = clampedMin,
            pathMinHitNumericFloor = rawMin < AimiRiskConstants.NUMERIC_FLOOR_MGDL,
        )
    }

    fun boundsFromPredictions(predBGs: Predictions?): PredictionPathBounds {
        val p = predBGs ?: return PredictionPathBounds(null, null, false)
        val rows = listOfNotNull(p.IOB, p.COB, p.UAM, p.ZT)
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return PredictionPathBounds(null, null, false)
        val clampedMins = rows.map { row -> row.minOf { it.toDouble() } }
        val clampedMin = clampedMins.min()
        return PredictionPathBounds(
            pathMinRawMgdl = null,
            pathMinClampedMgdl = clampedMin.takeIf { it.isFinite() },
            pathMinHitNumericFloor = clampedMin <= AimiRiskConstants.NUMERIC_FLOOR_MGDL + 0.5,
        )
    }

    fun safeFinite(value: Double): Double =
        if (value.isFinite()) value else Double.POSITIVE_INFINITY

    fun compositeMinMgdl(bg: Double, predTerminal: Double, eventualTerminal: Double): Double =
        minOf(
            safeFinite(bg),
            safeFinite(predTerminal),
            safeFinite(eventualTerminal),
        )
}
