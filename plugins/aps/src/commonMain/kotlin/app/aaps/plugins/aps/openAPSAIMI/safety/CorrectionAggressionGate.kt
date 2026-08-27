package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmtSigned1

/**
 * Classifies how aggressively AIMI may correct (TBR hyper kicker, rocket basal scale, hypo-block override).
 *
 * Whitelist meal/hyper signals (incl. unannounced meals) before blocking rebound-only rises.
 * Production analysis: search log tag [LOG_PREFIX].
 */
object CorrectionAggressionGate {

    const val LOG_PREFIX = "CORRECTION_AGGRESSION"

    /** Margin above [CorrectionAggressionInput.targetBg] for rebound guard (mg/dL). */
    const val REBOUND_BG_MARGIN_MGDL = 30.0

    /** Min BG in lookback window (mg/dL) that indicates recent hypo for rebound guard. */
    const val REBOUND_MIN_BG_LOOKBACK_MGDL = 75.0

    enum class Tier {
        /** Full correction: unannounced meal, hyper plateau, confirmed high rise. */
        FULL,
        /** Moderate correction: ambiguous rise without recent hypo rebound pattern. */
        MODERATE,
        /** Post-hypo rebound: no global hyper kicker / rocket / hypo override. */
        REBOUND_GUARD,
    }

    enum class PostHypoHint {
        NONE,
        REBOUND_SUSPECTED,
        MEAL_CONFIRMED,
    }

    data class Input(
        val bg: Double,
        val targetBg: Double,
        val deltaMgdl5m: Double,
        val shortAvgDelta: Double,
        val combinedDelta: Double,
        val cob: Double,
        val minBgLookback75m: Double,
        val estimatedCarbs: Double,
        val estimatedCarbsAgeMin: Double,
        val uamConfidence: Double,
        val estimatedRa: Double,
        val explicitMealMode: Boolean,
        val hasRecentMealEstimate: Boolean,
        val isConfirmedHighRise: Boolean,
        val postHypoHint: PostHypoHint = PostHypoHint.NONE,
    )

    data class Decision(
        val tier: Tier,
        val mealTierFull: Boolean,
        val allowGlobalHyperKicker: Boolean,
        val allowRocketBasalScale: Boolean,
        val allowRocketHypoOverride: Boolean,
        val maxBasalScaleCap: Double,
        val reasonTag: String,
    ) {
        fun toLogLine(input: Input): String {
            return buildString {
                append(LOG_PREFIX)
                append(": tier=").append(tier.name)
                append(" mealFull=").append(mealTierFull)
                append(" postHypo=").append(input.postHypoHint.name)
                append(" bg=").append(aimiFmt1(input.bg))
                append(" tgt=").append(aimiFmt1(input.targetBg))
                append(" minBg75=").append(
                    if (input.minBgLookback75m >= 500.0) "n/a" else aimiFmt0(input.minBgLookback75m)
                )
                append(" d5=").append(aimiFmtSigned1(input.deltaMgdl5m))
                append(" combD=").append(aimiFmt2(input.combinedDelta))
                append(" cob=").append(aimiFmt1(input.cob))
                append(" uam=").append(aimiFmt2(input.uamConfidence))
                append(" ra=").append(aimiFmt2(input.estimatedRa))
                append(" allowHyper=").append(allowGlobalHyperKicker)
                append(" allowRocketBasal=").append(allowRocketBasalScale)
                append(" allowRocketHypo=").append(allowRocketHypoOverride)
                append(" scaleCap=").append(aimiFmt1(maxBasalScaleCap))
                append(" tag=").append(reasonTag)
            }
        }
    }

    fun evaluate(input: Input): Decision {
        val mealFull = isMealTierFull(input)
        val reboundPattern = isReboundGuardPattern(input, mealFull)
        val tier = when {
            mealFull -> Tier.FULL
            reboundPattern -> Tier.REBOUND_GUARD
            else -> Tier.MODERATE
        }
        return buildDecision(input, tier, mealFull, reboundPattern)
    }

    /**
     * After [classifyPostHypoState]: tighten tier when rebound is confirmed but meal tier is not full.
     */
    fun refineForPostHypo(decision: Decision, input: Input, postHypoHint: PostHypoHint): Decision {
        if (postHypoHint != PostHypoHint.REBOUND_SUSPECTED) return decision
        if (hasIndependentMealEvidence(input)) return decision
        val reboundInput = input.copy(postHypoHint = postHypoHint)
        return buildDecision(
            reboundInput,
            Tier.REBOUND_GUARD,
            mealTierFull = false,
            reboundPattern = true,
            reasonSuffix = "post_hypo_rebound_refine",
        )
    }

    fun appendLogs(
        decision: Decision,
        input: Input,
        consoleLog: MutableList<String>,
        aapsLogger: AAPSLogger? = null,
    ) {
        val line = decision.toLogLine(input)
        consoleLog.add(line)
        aapsLogger?.debug(LTag.APS, line)
    }

    internal fun hasIndependentMealEvidence(input: Input): Boolean {
        if (input.explicitMealMode || input.hasRecentMealEstimate) return true
        if (input.cob >= 3.0) return true
        if (input.estimatedCarbs > 10.0 && input.estimatedCarbsAgeMin in 0.0..120.0) return true
        if (input.isConfirmedHighRise) return true
        return false
    }

    internal fun hasRiseDerivedMealSignals(input: Input): Boolean {
        if (input.bg > 145.0 && input.combinedDelta > 0.5) return true
        if (input.uamConfidence >= 0.55 && input.combinedDelta > 1.0) return true
        if (input.estimatedRa >= 0.8 && input.bg > 115.0) return true
        if (
            input.bg > 120.0 &&
            input.shortAvgDelta >= 2.5 &&
            input.deltaMgdl5m >= 2.0 &&
            input.minBgLookback75m >= 80.0
        ) {
            return true
        }
        return false
    }

    internal fun isMealTierFull(input: Input): Boolean {
        if (hasIndependentMealEvidence(input)) return true
        if (input.minBgLookback75m < REBOUND_MIN_BG_LOOKBACK_MGDL) {
            return false
        }
        return hasRiseDerivedMealSignals(input)
    }

    /**
     * Stricter unannounced-meal detection inside post-hypo recovery window (replaces score≥2 heuristic).
     */
    fun isMealLikelyPostHypoStrict(
        cob: Double,
        estimatedCarbs: Double,
        estimatedCarbsAgeMs: Long,
        uamConfidence: Double,
        bg: Double,
        shortAvgDelta: Float,
        delta: Float,
        recentBGs: List<Float>,
    ): Boolean {
        if (cob > 1.0) return true
        if (estimatedCarbs > 15.0 && estimatedCarbsAgeMs in 0..(90 * 60_000L)) return true
        if (uamConfidence >= 0.65) return true
        val sustainedRise = shortAvgDelta >= 3.0f && delta >= 2.0f &&
            recentBGs.take(3).zipWithNext().all { (prev, curr) -> curr > prev }
        return bg > 125.0 && sustainedRise
    }

    internal fun isReboundGuardPattern(input: Input, mealFull: Boolean): Boolean {
        if (mealFull) return false
        if (input.minBgLookback75m >= REBOUND_MIN_BG_LOOKBACK_MGDL) return false
        if (input.bg >= input.targetBg + REBOUND_BG_MARGIN_MGDL) return false
        if (input.postHypoHint == PostHypoHint.REBOUND_SUSPECTED) return true
        return true
    }

    private fun buildDecision(
        input: Input,
        tier: Tier,
        mealTierFull: Boolean,
        reboundPattern: Boolean,
        reasonSuffix: String? = null,
    ): Decision {
        val tag = when (tier) {
            Tier.FULL -> if (mealTierFull) "meal_or_hyper_full" else "full"
            Tier.MODERATE -> "moderate_rise"
            Tier.REBOUND_GUARD -> reasonSuffix ?: "post_hypo_rebound_guard"
        }
        return when (tier) {
            Tier.FULL -> Decision(
                tier = tier,
                mealTierFull = mealTierFull,
                allowGlobalHyperKicker = input.bg > input.targetBg + 15.0 ||
                    input.deltaMgdl5m > 5.0 ||
                    input.bg > input.targetBg + 40.0,
                allowRocketBasalScale = true,
                allowRocketHypoOverride = input.bg > input.targetBg + 35.0 || input.isConfirmedHighRise,
                maxBasalScaleCap = 10.0,
                reasonTag = tag,
            )
            Tier.MODERATE -> Decision(
                tier = tier,
                mealTierFull = mealTierFull,
                allowGlobalHyperKicker = input.bg > input.targetBg + 10.0 && input.deltaMgdl5m > 4.0,
                allowRocketBasalScale = input.deltaMgdl5m > 10.0 && input.bg > input.targetBg + 20.0,
                allowRocketHypoOverride = input.bg > input.targetBg + 50.0 && input.deltaMgdl5m > 8.0,
                maxBasalScaleCap = 3.0,
                reasonTag = tag,
            )
            Tier.REBOUND_GUARD -> Decision(
                tier = tier,
                mealTierFull = mealTierFull,
                allowGlobalHyperKicker = false,
                allowRocketBasalScale = false,
                allowRocketHypoOverride = false,
                maxBasalScaleCap = 1.5,
                reasonTag = tag,
            )
        }
    }
}
