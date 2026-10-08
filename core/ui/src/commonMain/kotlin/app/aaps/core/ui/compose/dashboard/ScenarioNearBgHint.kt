package app.aaps.core.ui.compose.dashboard

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import kotlin.math.abs

/**
 * True when Scenario (UAM) stays near current BG while Floor (IOB) still carries
 * a meaningful insulin-only slope — the “flat Scenario” UI case.
 */
internal fun scenarioNearBgEquilibriumHintVisible(
    predictions: List<BgDataPoint>,
    currentBgMgdl: Double?,
): Boolean {
    if (currentBgMgdl == null || !currentBgMgdl.isFinite()) return false
    val scenario = predictions.filter { it.type == BgType.UAM_PREDICTION }
    val floor = predictions.filter { it.type == BgType.IOB_PREDICTION }
    if (scenario.size < 3 || floor.size < 3) return false
    val bestMaxDev = scenario.maxOf { abs(it.value - currentBgMgdl) }
    val floorMaxDev = floor.maxOf { abs(it.value - currentBgMgdl) }
    return bestMaxDev <= 8.0 &&
        floorMaxDev >= 10.0 &&
        floorMaxDev >= bestMaxDev * 1.5
}
