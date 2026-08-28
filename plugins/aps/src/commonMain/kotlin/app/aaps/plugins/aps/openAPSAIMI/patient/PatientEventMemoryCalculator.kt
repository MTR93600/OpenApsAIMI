package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.TimestampedBgSample
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Time-windowed hypo/hyper load with decay so stale bucketed lows do not peg [PatientEventMemory.recentHypoLoad].
 */
object PatientEventMemoryCalculator {

    const val LOAD_LOOKBACK_MINUTES = 120
    const val HYPO_DECAY_HALF_LIFE_MINUTES = 90.0
    private const val HYPO_LOW_THRESHOLD_MGDL = 85.0

    fun compute(
        currentBgMgdl: Double,
        windowedSamples: List<TimestampedBgSample>,
        hypoFloor75m: Double,
        latentState: PhysioLatentState?,
        recoveryBurden: Double,
        nowMs: Long,
    ): PatientEventMemory {
        val windowValues = windowedSamples
            .map { it.bgMgdl }
            .filter { it.isFinite() && it > 39.0 }

        val hyperPeak = max(
            currentBgMgdl,
            windowValues.maxOrNull() ?: currentBgMgdl,
        )
        val hypoFloor = if (windowValues.isEmpty()) {
            min(currentBgMgdl, hypoFloor75m)
        } else {
            min(windowValues.min(), hypoFloor75m)
        }

        val hyperLoad = if (windowValues.isEmpty()) {
            ((currentBgMgdl - 180.0) / 120.0).coerceIn(0.0, 1.0)
        } else {
            windowValues.map { ((it - 180.0) / 120.0).coerceIn(0.0, 1.0) }.average()
        }

        val hypoLoadFromHistory = if (windowValues.isEmpty()) {
            ((72.0 - currentBgMgdl) / 25.0).coerceIn(0.0, 1.0)
        } else {
            windowValues.map { ((72.0 - it) / 25.0).coerceIn(0.0, 1.0) }.average()
        }

        val rawHypoLoad = max(
            hypoLoadFromHistory,
            ((75.0 - hypoFloor) / 25.0).coerceIn(0.0, 1.0),
        )
        val minutesSinceHypo = minutesSinceLastBgBelow(
            samples = windowedSamples,
            thresholdMgdl = HYPO_LOW_THRESHOLD_MGDL,
            nowMs = nowMs,
        )
        val hypoLoad = decayHypoLoad(
            rawHypoLoad = rawHypoLoad,
            minutesSinceLow = minutesSinceHypo,
            halfLifeMinutes = HYPO_DECAY_HALF_LIFE_MINUTES,
        )

        val hyperCrashScore = if (hyperPeak >= 180.0 && hypoFloor < HYPO_LOW_THRESHOLD_MGDL) {
            ((hyperPeak - 180.0) / 140.0).coerceIn(0.0, 1.0) *
                ((HYPO_LOW_THRESHOLD_MGDL - hypoFloor) / 25.0).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val postHyperExhaustionScore = maxOf(
            hyperCrashScore,
            (hyperLoad * 0.58) + (hypoLoad * 0.42),
            recoveryBurden * 0.65,
        ).coerceIn(0.0, 1.0)
        val correctionFragilityScore = maxOf(
            ((latentState?.postHypoReboundProb ?: 0.0) * 0.58) + (hypoLoad * 0.42),
            postHyperExhaustionScore * 0.72,
            recoveryBurden * 0.68,
        ).coerceIn(0.0, 1.0)

        return PatientEventMemory(
            recentHyperLoad = hyperLoad.coerceIn(0.0, 1.0),
            recentHypoLoad = hypoLoad.coerceIn(0.0, 1.0),
            postHyperExhaustionScore = postHyperExhaustionScore,
            correctionFragilityScore = correctionFragilityScore,
        )
    }

    internal fun minutesSinceLastBgBelow(
        samples: List<TimestampedBgSample>,
        thresholdMgdl: Double,
        nowMs: Long,
    ): Double {
        val latestLowMs = samples
            .asSequence()
            .filter { it.bgMgdl < thresholdMgdl }
            .maxOfOrNull { it.timestampMs }
            ?: return Double.POSITIVE_INFINITY
        return (nowMs - latestLowMs).coerceAtLeast(0L) / 60_000.0
    }

    internal fun decayHypoLoad(
        rawHypoLoad: Double,
        minutesSinceLow: Double,
        halfLifeMinutes: Double,
    ): Double {
        if (!rawHypoLoad.isFinite() || rawHypoLoad <= 0.0) return 0.0
        if (!minutesSinceLow.isFinite()) return 0.0
        val decay = exp(-ln(2.0) * minutesSinceLow / halfLifeMinutes)
        return (rawHypoLoad * decay).coerceIn(0.0, 1.0)
    }
}
