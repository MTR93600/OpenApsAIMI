package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.min

/**
 * Maps RBT resolver outputs to pump-path consumers (SafetyNet, LGS, stacking, Autodrive gater).
 *
 * Live dosing hints require [effectiveAuthority] != [ReleaseAuthority.NONE] (production authority).
 */
object RbtResolutionBridge {

    /** Blend for [HypoGuardMode.PARTIAL] min-pred lift (weaker than full ignore). */
    const val PARTIAL_CREDIBILITY_BLEND = 0.5

    data class AppliedHints(
        val ignoreMinPredictedCurve: Boolean,
        val partialMinPredCredibility: Boolean,
        val mealPriorityContext: Boolean,
        val suppressMealInterpretation: Boolean,
        val mealChannel: MealChannelHint?,
        val waitBiasMultiplier: Double,
        val chaosScore: Double,
        val episodeKind: RbtEpisodeMemory.EpisodeKind?,
        val summary: String,
    )

    fun apply(
        resolution: DoseChannelResolution?,
        effectiveAuthority: ReleaseAuthority?,
        chaos: RbtChaosEvaluator.Result?,
        episode: RbtEpisodeMemory.EpisodeState?,
        defaultMealPriority: Boolean,
    ): AppliedHints {
        if (resolution == null) {
            return AppliedHints(
                ignoreMinPredictedCurve = false,
                partialMinPredCredibility = false,
                mealPriorityContext = defaultMealPriority,
                suppressMealInterpretation = false,
                mealChannel = null,
                waitBiasMultiplier = 1.0,
                chaosScore = chaos?.score ?: 0.0,
                episodeKind = episode?.kind,
                summary = "RBT inactive",
            )
        }

        val authorityLive = effectiveAuthority != null && effectiveAuthority != ReleaseAuthority.NONE
        val ignoreMinPred = authorityLive && shouldIgnoreMinPredictedCurve(resolution)
        val partialMinPred = authorityLive && shouldApplyPartialMinPredCredibility(resolution)
        val mealChannel = if (authorityLive) resolution.mealChannel else MealChannelHint.NORMAL
        val suppressMeal = authorityLive && (
            mealChannel == MealChannelHint.SUPPRESS ||
                resolution.reasonCodes.any { it == "PATTERN_MEAL_SUPPRESS" || it == "UAM_MEAL_SUPPRESS" }
            )
        val mealPriority = if (!authorityLive) {
            defaultMealPriority
        } else {
            when (mealChannel) {
                MealChannelHint.PRIORITY -> !suppressMeal
                MealChannelHint.SUPPRESS -> false
                MealChannelHint.NORMAL -> defaultMealPriority && !suppressMeal
            }
        }
        val waitMul = if (authorityLive) {
            waitBiasMultiplier(resolution.waitBias)
        } else {
            1.0
        }
        val chaosActive = chaos?.active == true
        val adjustedWaitMul = if (authorityLive && chaosActive) {
            min(waitMul, 0.65)
        } else {
            waitMul
        }

        val parts = buildList {
            add("hypo=${resolution.hypoGuardMode.name}")
            add("meal=${mealChannel.name}")
            if (authorityLive) {
                add("wait=${"%.2f".format(resolution.waitBias)}→${"%.2f".format(adjustedWaitMul)}")
            }
            if (ignoreMinPred) add("minPredIgnored")
            if (partialMinPred) add("minPredPartial")
            effectiveAuthority?.let { add("auth=${it.name}") }
            chaos?.let { add("chaos=${"%.2f".format(it.score)}") }
            episode?.let { add("episode=${it.kind.name}") }
        }

        return AppliedHints(
            ignoreMinPredictedCurve = ignoreMinPred,
            partialMinPredCredibility = partialMinPred,
            mealPriorityContext = mealPriority,
            suppressMealInterpretation = suppressMeal,
            mealChannel = if (authorityLive) mealChannel else null,
            waitBiasMultiplier = adjustedWaitMul,
            chaosScore = chaos?.score ?: 0.0,
            episodeKind = episode?.kind,
            summary = parts.joinToString(" "),
        )
    }

    fun shouldIgnoreMinPredictedCurve(resolution: DoseChannelResolution?): Boolean {
        if (resolution == null) return false
        return when (resolution.hypoGuardMode) {
            HypoGuardMode.IGNORE_MINPRED -> true
            HypoGuardMode.PARTIAL -> false
            HypoGuardMode.FULL -> resolution.hypoMinPredIgnored
        }
    }

    fun shouldApplyPartialMinPredCredibility(resolution: DoseChannelResolution?): Boolean {
        if (resolution == null) return false
        return resolution.hypoGuardMode == HypoGuardMode.PARTIAL
    }

    fun waitBiasMultiplier(waitBias: Double): Double =
        (1.0 - (waitBias - 0.15).coerceAtLeast(0.0) * 0.75).coerceIn(0.55, 1.0)
}
