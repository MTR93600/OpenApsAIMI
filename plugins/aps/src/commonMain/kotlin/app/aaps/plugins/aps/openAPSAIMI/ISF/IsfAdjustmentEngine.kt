package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlin.math.ln
import kotlin.math.min

class IsfAdjustmentEngine(
    private val maxStepPctPerLoop: Double = 0.05,   // ±5%/loop
    private val maxStepPctPerHour: Double = 0.20    // ±20%/h (cumulé)
) {
    /**
     * The last published ISF and when it was published, together.
     *
     * One nullable object rather than two nullable fields on purpose: the rate limiter needs "have we
     * published anything yet" to be a single question. Splitting it into `lastIsf` and `lastTsMs` let
     * the first call fall into the limiter with an elapsed time of zero, which pinned the result to
     * the fallback value and silently discarded the blend.
     */
    private data class Anchor(val isf: Double, val tsMs: Long)

    private var anchor: Anchor? = null

    fun compute(
        bgKalman: Double,
        tddEma: Double,
        profileIsf: Double,
        sippConfidence: Double,
        kalmanVar: Double,
        nowMs: Long
    ): Double {
        val bg = bgKalman.coerceIn(80.0, 240.0)
        val denom = (ln(bg / 55.0)) * (tddEma * tddEma) * 0.02
        val isfAfRaw = (2300.0 / denom).coerceIn(0.3 * profileIsf, 1.7 * profileIsf)

        val kalmanTrust = (1.0 / (1.0 + kalmanVar)).coerceIn(0.0, 1.0)
        val wAf = (0.6 * kalmanTrust) * (1.0 - sippConfidence)
        val wProf = 1.0 - wAf

        var blended = wAf * isfAfRaw + wProf * profileIsf
        if (bgKalman < 90.0) blended = min(blended, profileIsf)

        val safe = rateLimit(blended, nowMs)
        anchor = Anchor(safe, nowMs)
        return safe
    }

    /** No anchor yet (first call of the process): the target is returned as is. */
    private fun rateLimit(target: Double, nowMs: Long): Double {
        val a = anchor ?: return target
        val current = a.isf
        val elapsedMs = (nowMs - a.tsMs).coerceAtLeast(0L)
        val elapsedMinutes = elapsedMs / 60000.0

        val hourlyBudgetPct = maxStepPctPerHour * (elapsedMinutes / 60.0)
        val allowedPct = min(maxStepPctPerLoop, hourlyBudgetPct)

        val maxUp = current * (1 + allowedPct)
        val maxDown = current * (1 - allowedPct)
        return target.coerceIn(maxDown, maxUp)
    }
}