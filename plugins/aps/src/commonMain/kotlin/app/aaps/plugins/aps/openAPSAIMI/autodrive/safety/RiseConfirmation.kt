package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

/**
 * Checks that a glucose rise is real before Autodrive V3 acts on it.
 *
 * It reads only the glucose history. It does not use the insulin model, the meal memory or the UAM
 * confidence, so it cannot confirm itself.
 *
 * The window is short when glucose is already high: above [FAST_WINDOW_BG_MGDL] two ticks (10 min)
 * are enough. At or below it, [SLOW_WINDOW_TICKS] ticks (30 min) are used, because a low or normal
 * glucose gives more time and more risk of acting on noise.
 */
object RiseConfirmation {

    enum class Level { NONE, WEAK, CONFIRMED }

    data class Result(
        val level: Level,
        val windowTicks: Int,
        val positiveShare: Double,
        val netRiseMgdl: Double,
    )

    const val FAST_WINDOW_BG_MGDL = 130.0
    const val FAST_WINDOW_TICKS = 2
    const val SLOW_WINDOW_TICKS = 6
    private const val MIN_POSITIVE_SHARE = 0.66
    private const val MIN_MEAN_DELTA_MGDL_PER_5MIN = 1.0

    /**
     * @param bgNewestFirst glucose values in mg/dL, one per 5 min, newest first. The first value is the current one.
     * @return null when there is not enough clean history; the caller must then keep its old behavior.
     */
    fun evaluate(bgNewestFirst: List<Double>): Result? {
        val current = bgNewestFirst.firstOrNull() ?: return null
        val ticks = if (current > FAST_WINDOW_BG_MGDL) FAST_WINDOW_TICKS else SLOW_WINDOW_TICKS
        if (bgNewestFirst.size < ticks + 1) return null

        val deltas = (0 until ticks).map { bgNewestFirst[it] - bgNewestFirst[it + 1] }
        val net = bgNewestFirst[0] - bgNewestFirst[ticks]
        val positive = deltas.count { it > 0.0 }
        val share = positive.toDouble() / ticks
        val meanDelta = net / ticks
        val latestRising = deltas.first() > 0.0

        val confirmed = latestRising && share >= MIN_POSITIVE_SHARE && meanDelta >= MIN_MEAN_DELTA_MGDL_PER_5MIN &&
            (ticks == FAST_WINDOW_TICKS || deltas.max() < net * 0.7)
        val level = when {
            confirmed -> Level.CONFIRMED
            latestRising && net > 0.0 -> Level.WEAK
            else -> Level.NONE
        }
        return Result(level, ticks, share, net)
    }
}
