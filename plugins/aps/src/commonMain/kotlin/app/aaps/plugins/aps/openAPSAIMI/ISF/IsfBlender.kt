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
    /**
     * The last published ISF and when it was published, together.
     *
     * One nullable object rather than two nullable fields on purpose: the rate limiter needs "have we
     * published anything yet" to be a single question. Splitting it into `lastIsf` and `lastTsMs` let
     * the first call fall into the limiter with an elapsed time of zero, which pinned the result to
     * the fused value and silently discarded the Kalman contribution.
     */
    private data class Anchor(val isf: Double, val tsMs: Long)

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
