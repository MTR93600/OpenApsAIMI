package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Parameters describing the insulin action model. */
data class PkPdParams(
    val diaHrs: Double,
    val peakMin: Double
)

/** Bounds and maximum daily change limits applied to the learned PK/PD parameters. */
data class PkPdBounds(
    val diaMinH: Double = 3.0,
    val diaMaxH: Double = 24.0,
    val peakMinMin: Double = 30.0,
    val peakMinMax: Double = 240.0,
    val maxDiaChangePerDayH: Double = 2.0,
    val maxPeakChangePerDayMin: Double = 20.0
)

interface Kernel {
    /** Instant insulin action (not cumulative) normalised so that the area equals 1 on [0, DIA]. */
    fun actionAt(minFromDose: Double, p: PkPdParams): Double

    /** Remaining IOB fraction. */
    fun iobResidual(minFromDose: Double, p: PkPdParams): Double = 1.0 - cdf(minFromDose, p)

    /** CDF of the insulin action (0..1). */
    fun cdf(minFromDose: Double, p: PkPdParams): Double
}

/** Log-normal kernel parameterised by peak time and DIA. */
class LogNormalKernel : Kernel {
    override fun actionAt(minFromDose: Double, p: PkPdParams): Double {
        if (minFromDose <= 0.0) return 0.0
        val tp = p.peakMin
        val sigma = 0.45
        val mu = ln(tp) - sigma * sigma
        val x = minFromDose
        val pdf = (1.0 / (x * sigma * sqrt(2.0 * PI))) * exp(-(ln(x) - mu).pow(2) / (2.0 * sigma * sigma))
        val scale = 1.0 / cdf(p.diaHrs * 60.0, p)
        return pdf * scale
    }

    override fun cdf(minFromDose: Double, p: PkPdParams): Double {
        if (minFromDose <= 0.0) return 0.0
        val tp = p.peakMin
        val sigma = 0.45
        val mu = ln(tp) - sigma * sigma
        val z = (ln(minFromDose) - mu) / (sigma * sqrt(2.0))
        return 0.5 * (1 + erf(z))
    }

    private fun erf(z: Double): Double {
        val t = 1.0 / (1.0 + 0.5 * abs(z))
        val ans = 1 - t * exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 +
            t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 +
            t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))))
        return if (z >= 0) ans else -ans
    }
}