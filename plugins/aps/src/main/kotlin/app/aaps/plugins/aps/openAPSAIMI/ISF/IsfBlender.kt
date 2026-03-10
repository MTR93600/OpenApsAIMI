package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlin.math.min

/**
 * Mélange un ISF "lent" (fusedIsf: PK/PD+TDD) avec un ISF "rapide" (kalmanIsf),
 * en respectant un plafond de variation par tick et au prorata du temps écoulé.
 */
class IsfBlender(
    private val maxStepPctPerLoop: Double = 0.05,   // ±5% / tick
    private val maxStepPctPerHour: Double = 0.20    // ±20% / h (cumulé)
) {
    private var lastIsf: Double? = null
    private var lastTsMs: Long? = null

    /**
     * @param fusedIsf    socle lent (PkPdIntegration.fusedIsf)
     * @param kalmanIsf   candidat rapide (KalmanISFCalculator)
     * @param trustFast   0..1 (poids du rapide) - ex: 1/(1+varianceKalman)
     * @param nowMs       System.currentTimeMillis()
     */
    fun blend(
        fusedIsf: Double,
        kalmanIsf: Double,
        trustFast: Double,
        nowMs: Long
    ): Double {
        val wFast = trustFast.coerceIn(0.0, 1.0)
        val wSlow = 1.0 - wFast
        val target = wSlow * fusedIsf + wFast * kalmanIsf
        val current = lastIsf ?: fusedIsf
        val safe = rateLimit(target, current, nowMs)
        lastIsf = safe
        lastTsMs = nowMs
        return safe
    }

    private fun rateLimit(target: Double, current: Double, nowMs: Long): Double {
        val elapsedMs = (nowMs - (lastTsMs ?: nowMs)).coerceAtLeast(0L)
        val hourlyBudgetPct = maxStepPctPerHour * (elapsedMs / 3600000.0)
        val allowedPct = min(maxStepPctPerLoop, hourlyBudgetPct)
        val maxUp = current * (1 + allowedPct)
        val maxDown = current * (1 - allowedPct)
        return target.coerceIn(maxDown, maxUp)
    }
}
