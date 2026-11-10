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

    /** Last returned ISF and the time it was set. Both are written together so they cannot drift apart. */
    private data class Anchor(val isf: Double, val tsMs: Long)

    // Volatile: the two old fields were written without any lock from two dispatchers
    // (Dispatchers.IO for the refreshes, Dispatchers.Default for the loop). Writing one
    // object keeps the value and its timestamp in sync.
    @Volatile
    private var anchor: Anchor? = null

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
        val safe = rateLimit(target, nowMs)
        anchor = Anchor(safe, nowMs)
        return safe
    }

    /** No anchor yet (first call of the process): the target is returned as is. */
    private fun rateLimit(target: Double, nowMs: Long): Double {
        val a = anchor ?: return target
        val current = a.isf
        val elapsedMs = (nowMs - a.tsMs).coerceAtLeast(0L)
        val hourlyBudgetPct = maxStepPctPerHour * (elapsedMs / 3600000.0)
        val allowedPct = min(maxStepPctPerLoop, hourlyBudgetPct)
        val maxUp = current * (1 + allowedPct)
        val maxDown = current * (1 - allowedPct)
        return target.coerceIn(maxDown, maxUp)
    }
}
