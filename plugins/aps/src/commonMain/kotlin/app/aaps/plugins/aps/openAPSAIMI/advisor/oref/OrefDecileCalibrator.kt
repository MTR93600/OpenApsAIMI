package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Monotone calibration curve from raw model scores to empirical outcome rates.
 * Bins sorted predictions into deciles; interpolates linearly between bin means (on-device, no sklearn).
 */
class OrefDecileCalibrator private constructor(
    private val rawKnots: DoubleArray,
    private val calKnots: DoubleArray,
) {

    fun apply(p: Double): Double {
        if (!p.isFinite()) return 0.0
        val x = p.coerceIn(0.0, 1.0)
        if (rawKnots.isEmpty()) return x
        if (x <= rawKnots.first()) return calKnots.first().coerceIn(0.0, 1.0)
        if (x >= rawKnots.last()) return calKnots.last().coerceIn(0.0, 1.0)
        var i = 0
        while (i < rawKnots.size - 1 && rawKnots[i + 1] < x) i++
        val x0 = rawKnots[i]
        val x1 = rawKnots[i + 1]
        val y0 = calKnots[i]
        val y1 = calKnots[i + 1]
        if (x1 - x0 < 1e-9) return y0.coerceIn(0.0, 1.0)
        val t = (x - x0) / (x1 - x0)
        return (y0 + t * (y1 - y0)).coerceIn(0.0, 1.0)
    }

    companion object {

        private const val MIN_SAMPLES = 200
        private const val BINS = 10

        /**
         * @param raw predicted probabilities [0,1]
         * @param actual binary outcomes 0/1
         */
        fun fitOrNull(raw: DoubleArray, actual: DoubleArray): OrefDecileCalibrator? {
            if (raw.size != actual.size || raw.size < MIN_SAMPLES) return null
            val n = raw.size
            val cut = n / 2
            val rateFirst = actual.copyOfRange(0, cut).average()
            val rateSecond = actual.copyOfRange(cut, n).average()
            val driftPp = abs(rateFirst - rateSecond) * 100.0
            if (driftPp > 5.0) {
                val targetMean = actual.average()
                val predMean = raw.average().coerceAtLeast(1e-9)
                val factor = targetMean / predMean
                val knots = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
                val cal = DoubleArray(5) { i -> (knots[i] * factor).coerceIn(0.0, 1.0) }
                return OrefDecileCalibrator(knots, cal)
            }

            val order = raw.indices.sortedBy { raw[it] }
            val perBin = max(1, n / BINS)
            val rList = ArrayList<Double>()
            val cList = ArrayList<Double>()
            var start = 0
            while (start < n) {
                val end = min(start + perBin, n)
                var sumR = 0.0
                var sumA = 0.0
                var c = 0
                for (k in start until end) {
                    val idx = order[k]
                    sumR += raw[idx]
                    sumA += actual[idx]
                    c++
                }
                if (c > 0) {
                    rList += sumR / c
                    cList += sumA / c
                }
                start = end
            }
            if (rList.size < 2) return null
            val rArr = rList.toDoubleArray()
            val cArr = cList.toDoubleArray()
            for (i in 1 until cArr.size) {
                if (cArr[i] < cArr[i - 1]) cArr[i] = cArr[i - 1]
            }
            return OrefDecileCalibrator(rArr, cArr)
        }
    }
}
