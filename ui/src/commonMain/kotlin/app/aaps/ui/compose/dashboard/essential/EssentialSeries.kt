package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import kotlin.math.abs

/** Lookups in the chart series that the skin reads. Pure, so they are tested without a screen. */
object EssentialSeries {

    /** The basal rate delivered at [timeMs]: the last step that started at or before it. */
    fun basalAt(actualBasal: List<GraphDataPoint>, timeMs: Long): Double? =
        actualBasal.filter { it.timestamp <= timeMs }.maxByOrNull { it.timestamp }?.value

    /** The SMB delivered within [windowMs] of [timeMs] (summed), or null when there was none. */
    fun smbAt(boluses: List<BolusGraphPoint>, timeMs: Long, windowMs: Long = 150_000L): Double? =
        boluses
            .filter { it.bolusType == BolusType.SMB && it.isValid && abs(it.timestamp - timeMs) <= windowMs }
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.amount }

    /**
     * The vertical range of the chart: at least [floor]..[ceiling] (both in the user's unit), and wider only
     * when a value leaves it. A day spent in range then still shows the target band as a band, with room
     * above and below, instead of a band that fills the whole chart.
     */
    fun chartScale(values: List<Double>, floor: Double, ceiling: Double): ClosedFloatingPointRange<Double> {
        val low = minOf(values.minOrNull() ?: floor, floor)
        val high = maxOf(values.maxOrNull() ?: ceiling, ceiling)
        return low..high
    }

    /**
     * The delivered basal as [count] bars of equal width from [fromMs] to [toMs]: each bar is the rate at
     * the start of its slot, or null before the first known rate.
     */
    fun basalBars(actualBasal: List<GraphDataPoint>, fromMs: Long, toMs: Long, count: Int): List<Double?> {
        if (count <= 0 || toMs <= fromMs) return emptyList()
        val sorted = actualBasal.sortedBy { it.timestamp }
        val slot = (toMs - fromMs).toDouble() / count
        return List(count) { i ->
            val t = fromMs + (slot * i).toLong()
            sorted.lastOrNull { it.timestamp <= t }?.value
        }
    }
}
