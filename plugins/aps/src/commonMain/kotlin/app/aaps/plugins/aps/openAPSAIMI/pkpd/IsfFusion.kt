package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.max
import kotlin.math.min

data class IsfFusionBounds(
    val minFactor: Double = 0.75,
    val maxFactor: Double = 1.25,
    val maxChangePer5Min: Double = 0.03
) {
    fun normalized(): IsfFusionBounds {
        val minF = minFactor.takeIf { it.isFinite() }?.coerceIn(0.3, 1.0) ?: 0.75
        val maxF = maxFactor.takeIf { it.isFinite() }?.coerceIn(1.0, 2.0) ?: 1.25
        val orderedMin = min(minF, maxF)
        val orderedMax = max(minF, maxF)
        val tick = maxChangePer5Min.takeIf { it.isFinite() }?.coerceIn(0.0, 0.84) ?: 0.03
        return copy(minFactor = orderedMin, maxFactor = orderedMax, maxChangePer5Min = tick)
    }
}

class IsfFusion(
    private val bounds: IsfFusionBounds = IsfFusionBounds()
) {
    private val safeBounds = bounds.normalized()
    private var lastIsf: Double? = null

    fun fused(
        profileIsf: Double,
        tddIsf: Double,
        pkpdScale: Double,
        isRising: Boolean = false,
        aggressionMultiplier: Double = 1.0
    ): Double {
        val profile = profileIsf.takeIf { it.isFinite() && it > 0.0 } ?: 20.0
        val tdd = tddIsf.takeIf { it.isFinite() && it > 0.0 } ?: profile
        val scale = pkpdScale.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val agg = aggressionMultiplier.takeIf { it.isFinite() && it > 0.0 } ?: 1.0

        val pkpdIsf = (tdd * scale).coerceAtLeast(1.0)
        val candidates = listOf(profile, tdd, pkpdIsf).sorted()
        var median = candidates[1]

        if (isRising && median > profile) {
            median = profile
        }

        median *= agg

        val minSafeIsf = min(profile, tdd * safeBounds.minFactor) * (if (isRising) 0.8 else 1.0)
        val maxSafeIsf = tdd * (safeBounds.maxFactor * 1.5)
        median = median.coerceInOrdered(minSafeIsf, maxSafeIsf)

        lastIsf?.let { prev ->
            val prevSafe = prev.takeIf { it.isFinite() && it > 0.0 } ?: median
            val maxUp = prevSafe * (1.0 + safeBounds.maxChangePer5Min)
            val maxDown = prevSafe * (0.85 - safeBounds.maxChangePer5Min)
            median = median.coerceInOrdered(maxDown, maxUp)
        }
        lastIsf = median
        return median.coerceAtLeast(1.0)
    }

    private fun Double.coerceInOrdered(lower: Double, upper: Double): Double {
        val lo = min(lower, upper)
        val hi = max(lower, upper)
        return coerceIn(lo, hi)
    }
}
