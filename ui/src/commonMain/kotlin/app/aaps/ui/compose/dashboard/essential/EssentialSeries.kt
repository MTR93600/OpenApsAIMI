package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.math.abs

/** Lookups in the chart series that the skin reads. Pure, so they are tested without a screen. */
object EssentialSeries {

    const val PREDICTION_HORIZON_MS = 60L * 60L * 1000L
    const val PREDICTION_TOLERANCE_MS = 5L * 60L * 1000L

    /** AIMI is driven by the unannounced-meal curve, so it comes first. COB is never used. */
    private val predictionOrder = listOf(BgType.UAM_PREDICTION, BgType.IOB_PREDICTION, BgType.ZT_PREDICTION)

    /**
     * The predicted glucose at now + 60 min: the point nearest that time on the first curve that has one
     * within ±5 min, in the order UAM, IOB, ZT. Null when no curve reaches that far.
     */
    fun prediction60(predictions: List<BgDataPoint>, nowMs: Long): BgDataPoint? {
        val at = nowMs + PREDICTION_HORIZON_MS
        for (type in predictionOrder) {
            val nearest = predictions
                .filter { it.type == type && abs(it.timestamp - at) <= PREDICTION_TOLERANCE_MS }
                .minByOrNull { abs(it.timestamp - at) }
            if (nearest != null) return nearest
        }
        return null
    }

    /** The basal rate delivered at [timeMs]: the last step that started at or before it. */
    fun basalAt(actualBasal: List<GraphDataPoint>, timeMs: Long): Double? =
        actualBasal.filter { it.timestamp <= timeMs }.maxByOrNull { it.timestamp }?.value

    /** The SMB delivered within [windowMs] of [timeMs] (summed), or null when there was none. */
    fun smbAt(boluses: List<BolusGraphPoint>, timeMs: Long, windowMs: Long = 150_000L): Double? =
        boluses
            .filter { it.bolusType == BolusType.SMB && it.isValid && abs(it.timestamp - timeMs) <= windowMs }
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.amount }
}
