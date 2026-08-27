package app.aaps.plugins.aps.openAPSAIMI.learning

import kotlin.math.min

/**
 * Combines [BasalLearner] (H) and [BasalNeuralLearner] (N) universal multipliers.
 *
 * Defensive path (either < 0.99): take the more conservative factor.
 * Boost path: weighted blend instead of [kotlin.math.max] to avoid bistable pinning at H ceiling.
 */
object BasalAdaptiveMultiplier {

    private const val DEFENSIVE_THRESHOLD = 0.99
    private const val H_WEIGHT = 0.55
    private const val N_WEIGHT = 0.45

    fun combine(hMult: Double, nMult: Double): Double {
        return when {
            hMult < DEFENSIVE_THRESHOLD || nMult < DEFENSIVE_THRESHOLD -> min(hMult, nMult)
            else -> (hMult * H_WEIGHT + nMult * N_WEIGHT).coerceAtLeast(1.0)
        }
    }
}
