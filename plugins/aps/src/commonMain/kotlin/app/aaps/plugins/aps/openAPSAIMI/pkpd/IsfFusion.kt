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

    /** Last authoritative ISF and the time it was set. Both are written together so they cannot drift apart. */
    private data class Anchor(val isf: Double, val tsMs: Long)

    private var anchor: Anchor? = null

    /**
     * @param nowMs wall clock of this call, used to measure how much slew budget has built up.
     * @param authoritative true only for the one caller per tick that owns the slew anchor.
     *   A read-only caller gets a bounded value but does not move the anchor.
     */
    fun fused(
        profileIsf: Double,
        tddIsf: Double,
        pkpdScale: Double,
        nowMs: Long,
        authoritative: Boolean,
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

        anchor?.let { a ->
            val prev = a.isf.takeIf { it.isFinite() && it > 0.0 } ?: median
            // A backward clock jump gives a zero budget (freeze), never a negative one.
            val elapsedMs = (nowMs - a.tsMs).coerceAtLeast(0L)
            val ticks = (elapsedMs / NOMINAL_TICK_MS).coerceAtMost(MAX_CATCHUP_TICKS)
            val upFrac = safeBounds.maxChangePer5Min * ticks
            val downFrac = upFrac * DOWN_SLEW_GAIN
            val maxUp = prev * (1.0 + upFrac)
            val maxDown = prev * ((1.0 - downFrac).coerceAtLeast(MIN_DOWN_MULTIPLIER))
            median = median.coerceInOrdered(maxDown, maxUp)
        }
        val result = median.coerceAtLeast(1.0)
        // Re-stamp even after a backward jump: self-heals in one tick instead of freezing
        // until the clock catches up.
        if (authoritative) anchor = Anchor(result, nowMs)
        return result
    }

    private fun Double.coerceInOrdered(lower: Double, upper: Double): Double {
        val lo = min(lower, upper)
        val hi = max(lower, upper)
        return coerceIn(lo, hi)
    }

    private companion object {

        /** The user preference is worded "per tick", and one loop tick is 5 minutes. */
        const val NOMINAL_TICK_MS = 300_000.0

        /**
         * Downward slew stays faster on purpose (commit fe719f7efa, "massive meal response").
         * Expressed as a gain on the budget, not a fixed offset, so a zero budget closes BOTH bounds.
         * 0.40 * 1.375 = 0.55 -> maxDown = 0.45 * prev, same as the old "0.85 - 0.40" at the default setting.
         */
        const val DOWN_SLEW_GAIN = 1.375

        /** Never allow a single step below today's default worst case. */
        const val MIN_DOWN_MULTIPLIER = 0.45

        /** Cap the catch-up after a missed loop or a device sleep. */
        const val MAX_CATCHUP_TICKS = 2.0
    }
}
