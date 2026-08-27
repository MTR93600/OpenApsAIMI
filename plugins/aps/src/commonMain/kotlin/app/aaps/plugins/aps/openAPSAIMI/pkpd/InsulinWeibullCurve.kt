package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.exp
import kotlin.math.pow

/**
 * Shared Weibull insulin action curve (same kernel as [InsulinActionProfiler]).
 * Used for trajectory history samples so activity/stage align with the loop’s insulin model (TAP-G RFC C.2).
 */
object InsulinWeibullCurve {

    private const val SHAPE = 3.5

    /** Unnormalized action density (same formula as legacy [InsulinActionProfiler] private curve). */
    fun activityRaw(minutesSinceBolus: Double, peakTimeMinutes: Double): Double {
        if (minutesSinceBolus < 0 || peakTimeMinutes <= 0) return 0.0
        val shape = SHAPE
        val scale = peakTimeMinutes / ((shape - 1) / shape).pow(1 / shape)
        return (shape / scale) * (minutesSinceBolus / scale).pow(shape - 1) *
            exp(-(minutesSinceBolus / scale).pow(shape))
    }

    /** 0..1, peak at [peakTimeMinutes] maps to 1.0. */
    fun activityNormalized(minutesSinceBolus: Double, peakTimeMinutes: Double): Double {
        val peak = peakTimeMinutes.coerceAtLeast(30.0)
        val t = minutesSinceBolus.coerceAtLeast(0.0)
        val maxA = activityRaw(peak, peak).coerceAtLeast(1e-9)
        return (activityRaw(t, peak) / maxA).coerceIn(0.0, 1.0)
    }

    /**
     * Coarse [ActivityStage] from time-on-curve vs peak (not full PKPD kernel, but same peak geometry as profiler).
     */
    fun activityStage(minutesSinceBolus: Double, peakTimeMinutes: Double, iobU: Double): ActivityStage {
        if (iobU < 0.05) return ActivityStage.TAIL
        val p = peakTimeMinutes.coerceAtLeast(30.0)
        val t = minutesSinceBolus.coerceAtLeast(0.0)
        return when {
            t < p * 0.35 -> ActivityStage.RISING
            t <= p * 1.15 -> ActivityStage.PEAK
            t < p * 3.0 -> ActivityStage.FALLING
            else -> ActivityStage.TAIL
        }
    }
}
