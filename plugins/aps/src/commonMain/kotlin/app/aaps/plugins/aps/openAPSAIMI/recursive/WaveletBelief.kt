package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Haar wavelet decomposition (3 bands) on BG history — see AIMI_RECURSIVE_BELIEF.md §6.3.
 */
object WaveletBelief {

    data class Bands(
        val high: Double,
        val mid: Double,
        val low: Double,
    )

    /**
     * @param bgHistoryMgdl oldest-first BG samples (5-min spacing assumed)
     */
    fun decompose(bgHistoryMgdl: List<Double>): Bands? {
        if (bgHistoryMgdl.size < 8) return null
        val tail = bgHistoryMgdl.takeLast(48).filter { it.isFinite() }
        if (tail.size < 8) return null

        val level1 = haarLevel(tail)
        val level2 = haarLevel(level1.approx)
        val level3 = haarLevel(level2.approx)

        val high = rms(level1.detail)
        val mid = rms(level2.detail)
        val low = level3.approx.lastOrNull()?.let { abs(it - tail.average()) }
            ?: rms(level3.approx)
        return Bands(high = high, mid = mid, low = low)
    }

    private data class HaarLevel(val approx: List<Double>, val detail: List<Double>)

    private fun haarLevel(values: List<Double>): HaarLevel {
        val approx = mutableListOf<Double>()
        val detail = mutableListOf<Double>()
        var i = 0
        while (i + 1 < values.size) {
            val a = values[i]
            val b = values[i + 1]
            approx += (a + b) / 2.0
            detail += (a - b) / 2.0
            i += 2
        }
        if (values.size % 2 == 1) {
            approx += values.last()
        }
        return HaarLevel(approx, detail)
    }

    private fun rms(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sumSq = values.sumOf { it * it }
        return sqrt(sumSq / values.size)
    }
}
