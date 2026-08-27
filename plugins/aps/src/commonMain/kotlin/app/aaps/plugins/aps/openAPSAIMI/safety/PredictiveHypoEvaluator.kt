package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1

/**
 * Unified predictive hypo evaluation for [HypoGuard], [resolveSafetyStart], and `DetermineBasalAIMI2.setTempBasal`.
 * Keeps rising-gate, meal-context, and hyper-artefact rules in one place.
 */
object PredictiveHypoConstants {
    const val TOLERANCE_MGDL = 5.0
    const val RISING_FAST_DELTA = 4.0
    const val RISING_MODERATE_DELTA = 2.0
    const val FAST_FALL_DELTA = -2.0
    const val DEFAULT_HYPER_ARTIFACT_MARGIN_MGDL = 40.0
}

data class PredictiveHypoInput(
    val bgNow: Double,
    val predicted: Double,
    val eventual: Double,
    val hypoThreshold: Double,
    val delta: Double,
    val mealContext: MealSafetyContext = MealSafetyContext(),
    val hyperArtifactMarginMgdl: Double = PredictiveHypoConstants.DEFAULT_HYPER_ARTIFACT_MARGIN_MGDL,
)

data class PredictiveSuppression(
    val suppressed: Boolean,
    val risingFast: Boolean = false,
    val risingModerate: Boolean = false,
    val hyperArtifact: Boolean = false,
    val mealContext: Boolean = false,
)

object PredictiveHypoEvaluator {

    fun floor(hypoThreshold: Double): Double = hypoThreshold - PredictiveHypoConstants.TOLERANCE_MGDL

    fun isStrongNow(bgNow: Double, hypoThreshold: Double): Boolean =
        bgNow <= floor(hypoThreshold)

    fun evaluateSuppression(input: PredictiveHypoInput): PredictiveSuppression {
        if (input.mealContext.hasMealIntent &&
            input.bgNow >= input.hypoThreshold &&
            input.delta > PredictiveHypoConstants.FAST_FALL_DELTA
        ) {
            return PredictiveSuppression(
                suppressed = true,
                mealContext = true,
            )
        }

        val risingFast = input.delta >= PredictiveHypoConstants.RISING_FAST_DELTA
        val risingModerate =
            input.delta >= PredictiveHypoConstants.RISING_MODERATE_DELTA && input.bgNow > input.hypoThreshold
        val hyperArtifact = input.bgNow >= input.hypoThreshold + input.hyperArtifactMarginMgdl

        return PredictiveSuppression(
            suppressed = risingFast || risingModerate || hyperArtifact,
            risingFast = risingFast,
            risingModerate = risingModerate,
            hyperArtifact = hyperArtifact,
        )
    }

    fun isPredictiveSuppressed(input: PredictiveHypoInput): Boolean =
        evaluateSuppression(input).suppressed

    /**
     * Same contract as legacy [HypoGuard.isBelowHypoThreshold].
     */
    fun isBelowHypoThreshold(
        bgNow: Double,
        predicted: Double,
        eventual: Double,
        hypo: Double,
        delta: Double,
        mealContext: MealSafetyContext = MealSafetyContext(),
    ): Boolean {
        val input = PredictiveHypoInput(
            bgNow = bgNow,
            predicted = predicted,
            eventual = eventual,
            hypoThreshold = hypo,
            delta = delta,
            mealContext = mealContext,
        )

        if (isStrongNow(bgNow, hypo)) return true

        val suppression = evaluateSuppression(input)
        if (suppression.risingFast) return false

        val floor = floor(hypo)
        val strongFuture = predicted <= floor && eventual <= floor
        if (strongFuture && suppression.risingModerate) {
            // Continue to fastFall check only
        } else if (strongFuture && !suppression.suppressed) {
            return true
        }

        return delta <= PredictiveHypoConstants.FAST_FALL_DELTA && predicted <= hypo
    }

    fun formatSuppressionLogLine(
        input: PredictiveHypoInput,
        suppression: PredictiveSuppression,
        predNow: Double,
        eventualNow: Double,
    ): String? {
        if (!suppression.suppressed) return null
        val predLow = predNow < input.hypoThreshold
        val eventualLow = eventualNow < input.hypoThreshold
        if (!predLow && !eventualLow) return null
        return "🟢 SAFETY_LGS_RISING_GATE: halt prédictive supprimée — BG=${input.bgNow.toInt()} " +
            "Δ=${aimiFmt1(input.delta)} " +
            "(risingModerate=${suppression.risingModerate} hyperArtifact=${suppression.hyperArtifact} " +
            "mealContext=${suppression.mealContext}) " +
            "pred=${predNow.toInt()} ev=${eventualNow.toInt()} Th=${input.hypoThreshold.toInt()}"
    }
}

enum class LgsTierKind {
    TIER1_BG_REAL,
    TIER2_PRED_LOW,
    TIER3_EVENTUAL_LOW,
}

data class LgsTierMatch(
    val tier: LgsTierKind,
    val haltRemainingPipeline: Boolean,
)

object LgsTierRules {
    fun haltRemainingPipeline(tier: LgsTierKind): Boolean = when (tier) {
        LgsTierKind.TIER1_BG_REAL -> true
        LgsTierKind.TIER2_PRED_LOW -> false
        LgsTierKind.TIER3_EVENTUAL_LOW -> false
    }

    fun resolveTier(
        bgNow: Double,
        predNow: Double,
        eventualNow: Double,
        lgsTh: Double,
        delta: Float,
        predictiveSuppressed: Boolean,
    ): LgsTierMatch? {
        val tier1BgReal = bgNow < lgsTh || (bgNow < 70.0 && delta < 0f)
        if (tier1BgReal) {
            return LgsTierMatch(LgsTierKind.TIER1_BG_REAL, haltRemainingPipeline(LgsTierKind.TIER1_BG_REAL))
        }
        if (!predictiveSuppressed) {
            if (predNow < lgsTh && bgNow >= lgsTh) {
                return LgsTierMatch(LgsTierKind.TIER2_PRED_LOW, haltRemainingPipeline(LgsTierKind.TIER2_PRED_LOW))
            }
            if (eventualNow < lgsTh) {
                return LgsTierMatch(LgsTierKind.TIER3_EVENTUAL_LOW, haltRemainingPipeline(LgsTierKind.TIER3_EVENTUAL_LOW))
            }
        }
        return null
    }
}
