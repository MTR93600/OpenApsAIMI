package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Simplified learner that turns existing loop signals (autosens, DIA, noise…) into
 * the parameters expected by the night growth resistance monitor. No user-facing
 * tuning is required anymore: the heuristics adapt on top of data that the loop
 * already tracks.
 */
class NightGrowthResistanceLearner {

    data class Input(
        val ageYears: Int,
        val autosensRatio: Double,
        val diaMinutes: Int,
        val isfMgdl: Double,
        val targetBg: Double,
        val basalRate: Double,
        val stabilityMinutes: Double,
        val combinedDelta: Double,
        val bgNoise: Double
    )

    data class Output(
        val minRiseSlope: Double,
        val minDurationMinutes: Int,
        val minEventualOverTarget: Int,
        val smbBoost: Double,
        val basalBoost: Double,
        val maxSmbClamp: Double,
        val decayMinutes: Int
    )

    fun derive(input: Input): Output {
        val autosensExcess = (input.autosensRatio - 1.0)
        val resistance = autosensExcess.coerceAtLeast(0.0)

        val ageBonus = when {
            input.ageYears <= 7 -> -0.8
            input.ageYears <= 12 -> -0.4
            input.ageYears <= 16 -> 0.0
            else -> 0.2
        }
        val stabilityBonus = min(1.2, input.stabilityMinutes / 30.0)
        val noisePenalty = min(2.0, input.bgNoise / 2.0)

        val minRiseSlope = (4.5 + ageBonus - stabilityBonus + noisePenalty + resistance * 3.2)
            .coerceIn(3.0, 8.0)

        val diaInfluence = (input.diaMinutes / 60.0 * 6.0).coerceIn(20.0, 50.0)
        val persistenceBonus = min(15.0, abs(input.combinedDelta))
        val minDuration = (diaInfluence + resistance * 25.0 + persistenceBonus)
            .roundToInt()
            .coerceIn(20, 60)

        val baseEventual = max(12.0, input.isfMgdl * 0.12)
        val resistanceMargin = resistance * input.isfMgdl * 0.25
        val targetBias = ((input.targetBg - 100.0) / 20.0).coerceIn(-4.0, 6.0)
        val minEventual = (baseEventual + resistanceMargin + targetBias)
            .roundToInt()
            .coerceIn(10, 45)

        val smbBoost = (1.1 + resistance * 0.6 + min(0.1, input.basalRate * 0.05))
            .coerceIn(1.05, 1.45)
        val basalBoost = (1.05 + resistance * 0.4 + min(0.15, stabilityBonus * 0.1))
            .coerceIn(1.02, 1.35)

        val maxSmbClamp = min(1.5, max(0.2, input.basalRate * 0.35 + resistance * 0.5))

        val decay = max(15, min(45, (input.diaMinutes / 6.0).roundToInt()))

        return Output(
            minRiseSlope = minRiseSlope,
            minDurationMinutes = minDuration,
            minEventualOverTarget = minEventual,
            smbBoost = smbBoost,
            basalBoost = basalBoost,
            maxSmbClamp = maxSmbClamp,
            decayMinutes = decay
        )
    }
}
