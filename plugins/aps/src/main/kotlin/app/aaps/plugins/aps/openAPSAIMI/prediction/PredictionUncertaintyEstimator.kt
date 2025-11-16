package app.aaps.plugins.aps.openAPSAIMI.prediction

import app.aaps.core.interfaces.aps.PredictionPercentileBand
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class PredictionScenario(val code: String) {
    IOB("IOB"),
    UAM("UAM"),
    ZT("ZT")
}

object PredictionUncertaintyEstimator {

    data class Input(
        val combinedDelta: Double?,
        val parabolaMinutes: Double?,
        val slopeFromDeviations: Double,
        val nightGrowthCandidate: Boolean,
        val pkpdTailFraction: Double?,
        val carbsActive: Double,
        val mealModeActive: Boolean,
        val lateFatRise: Boolean
    )

    data class SeriesSnapshot(
        val iob: List<Double>,
        val uam: List<Double>?,
        val zt: List<Double>
    )

    fun scenarioWeights(input: Input): Map<PredictionScenario, Double> {
        var iob = 0.4
        var uam = 0.35
        var zt = 0.25

        val combined = input.combinedDelta ?: 0.0
        if (combined > 0.6) {
            uam += min(0.25, combined / 8.0)
        } else if (combined < -0.6) {
            zt += min(0.25, abs(combined) / 8.0)
        }

        when {
            input.slopeFromDeviations > 0.3 -> uam += 0.1
            input.slopeFromDeviations < -0.3 -> zt += 0.1
        }

        val parabola = input.parabolaMinutes ?: 0.0
        if (parabola in 10.0..50.0 && combined > 0) {
            uam += 0.05
        }

        if (input.nightGrowthCandidate) {
            uam += 0.05
        }

        val tailFraction = input.pkpdTailFraction ?: 0.0
        when {
            tailFraction > 0.4 -> iob += 0.1
            tailFraction < 0.1 -> zt += 0.05
        }

        if (input.carbsActive < 5.0 && !input.mealModeActive) {
            iob += 0.05
        }

        if (input.lateFatRise) {
            uam += 0.08
        }

        val minFloor = 0.01
        iob = max(minFloor, iob)
        uam = max(minFloor, uam)
        zt = max(minFloor, zt)
        val total = iob + uam + zt
        return mapOf(
            PredictionScenario.IOB to iob / total,
            PredictionScenario.UAM to uam / total,
            PredictionScenario.ZT to zt / total
        )
    }

    fun percentileBands(
        weights: Map<PredictionScenario, Double>,
        snapshot: SeriesSnapshot,
        stepMinutes: Int = 5
    ): List<PredictionPercentileBand> {
        val maxSize = max(snapshot.iob.size, max(snapshot.uam?.size ?: 0, snapshot.zt.size))
        if (maxSize <= 1) return emptyList()
        val bands = mutableListOf<PredictionPercentileBand>()
        for (index in 1 until maxSize) {
            val bucket = mutableListOf<Pair<Double, Double>>()
            snapshot.iob.getOrNull(index)?.let { bucket.add(it to (weights[PredictionScenario.IOB] ?: 0.0)) }
            snapshot.uam?.getOrNull(index)?.let { bucket.add(it to (weights[PredictionScenario.UAM] ?: 0.0)) }
            snapshot.zt.getOrNull(index)?.let { bucket.add(it to (weights[PredictionScenario.ZT] ?: 0.0)) }
            if (bucket.isEmpty()) continue
            val normalized = normalizeWeights(bucket)
            val p05 = weightedQuantile(normalized, 0.05)
            val p50 = weightedQuantile(normalized, 0.5)
            val p95 = weightedQuantile(normalized, 0.95)
            val offset = ceil(index * stepMinutes.toDouble()).toInt()
            bands.add(
                PredictionPercentileBand(
                    offsetMinutes = offset,
                    p05 = clamp(p05),
                    p50 = clamp(p50),
                    p95 = clamp(p95)
                )
            )
        }
        return bands
    }

    private fun normalizeWeights(bucket: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val total = bucket.sumOf { (_, w) -> if (w.isFinite() && w > 0) w else 0.0 }
        val fallback = if (total <= 0.0) 1.0 / bucket.size else 0.0
        return bucket.map { (value, weight) ->
            val w = if (total <= 0.0) fallback else weight / total
            value to w
        }
    }

    private fun weightedQuantile(bucket: List<Pair<Double, Double>>, quantile: Double): Double {
        if (bucket.isEmpty()) return Double.NaN
        val sorted = bucket.sortedBy { it.first }
        var cumulative = 0.0
        val target = quantile.coerceIn(0.0, 1.0)
        for ((value, weight) in sorted) {
            cumulative += weight
            if (cumulative >= target) return value
        }
        return sorted.last().first
    }

    private fun clamp(value: Double): Int {
        if (!value.isFinite()) return 0
        return value.coerceIn(39.0, 401.0).toInt()
    }
}
