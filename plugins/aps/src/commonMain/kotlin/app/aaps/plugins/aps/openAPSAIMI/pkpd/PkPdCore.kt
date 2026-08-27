package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Parameters describing the insulin action model. */
data class PkPdParams(
    val diaHrs: Double,
    val peakMin: Double
)

/** Bounds and maximum daily change limits applied to the learned PK/PD parameters. */
data class PkPdBounds(
    val diaMinH: Double = 3.0,
    val diaMaxH: Double = 8.0,
    val peakMinMin: Double = 30.0,
    val peakMinMax: Double = 180.0,
    val maxDiaChangePerDayH: Double = 1.0,
    val maxPeakChangePerDayMin: Double = 10.0
) {
    /** Ensures min ≤ max so [kotlin.ranges.coerceIn] on DIA/peak never throws after bad prefs or merges. */
    fun normalized(): PkPdBounds {
        val diaLo = min(diaMinH, diaMaxH)
        val diaHi = max(diaMinH, diaMaxH)
        val peakLo = min(peakMinMin, peakMinMax)
        val peakHi = max(peakMinMin, peakMinMax)
        return copy(
            diaMinH = diaLo,
            diaMaxH = diaHi,
            peakMinMin = peakLo,
            peakMinMax = peakHi,
            maxDiaChangePerDayH = maxDiaChangePerDayH.coerceAtLeast(0.0),
            maxPeakChangePerDayMin = maxPeakChangePerDayMin.coerceAtLeast(0.0),
        )
    }
}

/** Sanitizes learned PK/PD state read from preferences before use in the estimator/kernel. */
fun sanitizePkPdParams(params: PkPdParams): PkPdParams {
    val dia = params.diaHrs.takeIf { it.isFinite() && it > 0.0 } ?: 6.0
    val peak = params.peakMin.takeIf { it.isFinite() && it > 1.0 } ?: 75.0
    return PkPdParams(dia, peak)
}

interface Kernel {
    /** Instant insulin action (not cumulative) normalised so that the area equals 1 on [0, DIA]. */
    fun actionAt(minFromDose: Double, p: PkPdParams): Double

    /** Remaining IOB fraction. */
    fun iobResidual(minFromDose: Double, p: PkPdParams): Double = 1.0 - cdf(minFromDose, p)

    /** CDF of the insulin action (0..1). */
    fun cdf(minFromDose: Double, p: PkPdParams): Double
}

/**
 * Exponential insulin kernel (the oref / AAPS model), driven by peak time **and** DIA.
 *
 * Both parameters shape the curve: DIA sets the length of the tail, the peak sets when the
 * action is strongest. This matters for learning, because the estimator can only learn DIA
 * if the predicted action really changes when DIA changes.
 *
 * The internal time constant `tau` grows without bound when the peak gets close to half of
 * the DIA, and turns negative past that point. To stay safe for any setting the peak used
 * here is clamped to [PEAK_MAX_FRACTION_OF_DIA] of the DIA.
 */
class ExponentialKernel : Kernel {

    override fun actionAt(minFromDose: Double, p: PkPdParams): Double {
        if (minFromDose <= 0.0) return 0.0
        val s = shape(p) ?: return 0.0
        if (minFromDose >= s.diaMin) return 0.0
        val value = (s.scale / (s.tau * s.tau)) * minFromDose * (1.0 - minFromDose / s.diaMin) * exp(-minFromDose / s.tau)
        return if (value.isFinite()) max(0.0, value) else 0.0
    }

    override fun cdf(minFromDose: Double, p: PkPdParams): Double = 1.0 - iobResidual(minFromDose, p)

    override fun iobResidual(minFromDose: Double, p: PkPdParams): Double {
        if (minFromDose <= 0.0) return 1.0
        // Broken parameters: report "insulin still on board", the side that never asks for more insulin.
        val s = shape(p) ?: return 1.0
        if (minFromDose >= s.diaMin) return 0.0
        val t = minFromDose
        val inner = (t * t / (s.tau * s.diaMin * (1.0 - s.a)) - t / s.tau - 1.0) * exp(-t / s.tau) + 1.0
        val value = 1.0 - s.scale * (1.0 - s.a) * inner
        return if (value.isFinite()) value.coerceIn(0.0, 1.0) else 1.0
    }

    /** Precomputed curve constants, or null when the parameters cannot give a usable curve. */
    private data class Shape(val diaMin: Double, val tau: Double, val a: Double, val scale: Double)

    private fun shape(p: PkPdParams): Shape? {
        val diaMin = p.diaHrs * 60.0
        if (!diaMin.isFinite() || diaMin <= 0.0) return null
        val rawPeak = p.peakMin
        if (!rawPeak.isFinite() || rawPeak <= 0.0) return null
        val peak = min(rawPeak, PEAK_MAX_FRACTION_OF_DIA * diaMin)
        val tau = peak * (1.0 - peak / diaMin) / (1.0 - 2.0 * peak / diaMin)
        if (!tau.isFinite() || tau <= 0.0) return null
        val a = 2.0 * tau / diaMin
        val denominator = 1.0 - a + (1.0 + a) * exp(-diaMin / tau)
        if (!denominator.isFinite() || abs(denominator) < 1e-9) return null
        val scale = 1.0 / denominator
        if (!scale.isFinite()) return null
        return Shape(diaMin, tau, a, scale)
    }

    companion object {

        /** Keeps the peak away from DIA/2, where the time constant blows up and then flips sign. */
        const val PEAK_MAX_FRACTION_OF_DIA = 0.45
    }
}

data class InsulinActivityWindow(
    val onsetMin: Double,
    val peakMin: Double,
    val offsetMin: Double,
    val diaMin: Double
) {
    fun normalizedPosition(minFromDose: Double): Double {
        if (offsetMin <= onsetMin) return 1.0
        return when {
            minFromDose <= onsetMin -> 0.0
            minFromDose >= offsetMin -> 1.0
            else -> (minFromDose - onsetMin) / (offsetMin - onsetMin)
        }
    }

    fun postWindowFraction(minFromDose: Double): Double {
        if (diaMin <= offsetMin) return 1.0
        return when {
            minFromDose <= offsetMin -> 0.0
            minFromDose >= diaMin -> 1.0
            else -> (minFromDose - offsetMin) / (diaMin - offsetMin)
        }
    }
}

enum class InsulinActivityStage {
    PRE_ONSET,
    RISING,
    PEAK,
    TAIL,
    EXHAUSTED
}

data class InsulinActivityState(
    val window: InsulinActivityWindow,
    val relativeActivity: Double,
    val normalizedPosition: Double,
val postWindowFraction: Double,
    val anticipationWeight: Double,
    val minutesUntilOnset: Double,
    val stage: InsulinActivityStage
)

fun Kernel.normalizedCdf(minFromDose: Double, p: PkPdParams): Double {
    if (minFromDose <= 0.0) return 0.0
    val dia = p.diaHrs * 60.0
    val total = cdf(dia, p).coerceAtLeast(1e-6)
    val partial = cdf(min(minFromDose, dia), p)
    return (partial / total).coerceIn(0.0, 1.0)
}

fun Kernel.findTimeForNormalizedCdf(target: Double, p: PkPdParams): Double {
    val dia = p.diaHrs * 60.0
    val clampedTarget = target.coerceIn(0.0, 1.0)
    if (clampedTarget <= 0.0) return 0.0
    if (clampedTarget >= 1.0) return dia
    var lo = 0.0
    var hi = dia
    repeat(50) {
        val mid = 0.5 * (lo + hi)
        val value = normalizedCdf(mid, p)
        if (value < clampedTarget) lo = mid else hi = mid
    }
    return hi.coerceIn(0.0, dia)
}

fun Kernel.activityWindow(
    p: PkPdParams,
    onsetFraction: Double = 0.1,
    offsetFraction: Double = 0.9
): InsulinActivityWindow {
    val dia = p.diaHrs * 60.0
    val onset = findTimeForNormalizedCdf(onsetFraction, p)
    val offset = findTimeForNormalizedCdf(offsetFraction, p).coerceIn(onset, dia)
    return InsulinActivityWindow(onset, p.peakMin, offset, dia)
}