package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.max

internal object AutodriveBasalPolicy {

    fun tierFactor(
        stateReason: String,
        bgMgdl: Double,
        targetBgMgdl: Double,
    ): Double {
        if (!stateReason.startsWith("Early")) return 1.0
        return when {
            bgMgdl >= max(250.0, targetBgMgdl + 130.0) -> 1.0
            bgMgdl >= max(200.0, targetBgMgdl + 90.0) -> 0.75
            else -> 0.5
        }
    }

    fun adaptiveMultiplierForDirectTbr(
        requestedRateUph: Double,
        bgMgdl: Double,
        targetBgMgdl: Double,
        profileMaxBasalUph: Double,
        learnedAdaptiveMultiplier: Double,
    ): Double {
        if (learnedAdaptiveMultiplier >= 1.0) return learnedAdaptiveMultiplier
        if (profileMaxBasalUph <= 0.0) return learnedAdaptiveMultiplier

        val severeHyper = bgMgdl >= max(250.0, targetBgMgdl + 130.0)
        val nearConfiguredCap = requestedRateUph >= profileMaxBasalUph * 0.90

        return if (severeHyper && nearConfiguredCap) 1.0 else learnedAdaptiveMultiplier
    }
}
