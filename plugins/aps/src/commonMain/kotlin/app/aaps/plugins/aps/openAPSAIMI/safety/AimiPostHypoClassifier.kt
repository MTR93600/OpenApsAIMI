package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath

/**
 * Post-hypo disambiguation lifted out of the tick.
 *
 * `targetBg` is the tick member, passed in. The clock and the UAM confidence read stay
 * at the call site: confidence is a lambda so it runs only on the recovery-window branch,
 * which is the only branch the ref reads it on.
 */
internal sealed class AimiPostHypoState {
    data object None : AimiPostHypoState()
    data class ReboundSuspected(val sinceMs: Long) : AimiPostHypoState()
    data class MealConfirmed(val sinceMs: Long) : AimiPostHypoState()
}

internal object AimiPostHypoClassifier {

    data class Step(
        val state: AimiPostHypoState,
        val lastHypoBelow70At: Long,
        val reason: String,
    )

    fun classify(
        recentBGs: List<Float>,
        cob: Double,
        explicitMealMode: Boolean,
        shortAvgDelta: Float,
        delta: Float,
        slopeFromMinDeviation: Double,
        estimatedCarbs: Double,
        estimatedCarbsAgeMs: Long,
        localHour: Int,
        targetBg: Double,
        lastHypoBelow70At: Long,
        now: Long,
        uamConfidence: () -> Double,
    ): Step {
        var stamp = lastHypoBelow70At
        val recentHypo = recentBGs.take(12).any { it < 70f }
        if (recentHypo) stamp = now
        val sinceHypoMs = if (stamp > 0L) now - stamp else Long.MAX_VALUE
        if (stamp == 0L || sinceHypoMs > 45 * 60_000L) {
            return Step(AimiPostHypoState.None, 0L, "")
        }
        val bgNow = recentBGs.firstOrNull()?.toDouble() ?: 0.0
        val targetNow = targetBg.takeIf { it > 0.0 } ?: 100.0
        if (PostHypoAggressiveRiseExit.shouldExit(bgNow, targetNow, delta.toDouble())) {
            return Step(
                AimiPostHypoState.None,
                0L,
                "🚀 POST_HYPO_AGGRESSIVE_RISE_EXIT: bg=${aimiFmt0(bgNow)} " +
                    "target+30=${aimiFmt0(targetNow + 30.0)} Δ=${aimiFmt1(delta)} → normal\n",
            )
        }
        val inPostHypoRecoveryWindow = sinceHypoMs <= 45 * 60_000L
        val isMealContext = explicitMealMode || cob > 0.5 ||
            if (inPostHypoRecoveryWindow) {
                CorrectionAggressionGate.isMealLikelyPostHypoStrict(
                    cob = cob,
                    estimatedCarbs = estimatedCarbs,
                    estimatedCarbsAgeMs = estimatedCarbsAgeMs,
                    uamConfidence = uamConfidence(),
                    bg = recentBGs.firstOrNull()?.toDouble() ?: 0.0,
                    shortAvgDelta = shortAvgDelta,
                    delta = delta,
                    recentBGs = recentBGs,
                )
            } else {
                AimiTickPolicyMath.isMealLikelyWithoutDeclaration(
                    shortAvgDelta, delta, slopeFromMinDeviation,
                    recentBGs, estimatedCarbs, estimatedCarbsAgeMs, localHour,
                )
            }
        return if (isMealContext) {
            if (sinceHypoMs > 30 * 60_000L) {
                Step(AimiPostHypoState.None, 0L, "")
            } else {
                Step(
                    AimiPostHypoState.MealConfirmed(sinceHypoMs),
                    stamp,
                    "🍽️ POST_HYPO_MEAL: Repas confirmé post-hypo (COB=${aimiFmt1(cob)}g slope=${aimiFmt1(slopeFromMinDeviation)})\n",
                )
            }
        } else if (sinceHypoMs > 30 * 60_000L) {
            Step(AimiPostHypoState.None, 0L, "")
        } else {
            Step(
                AimiPostHypoState.ReboundSuspected(sinceHypoMs),
                stamp,
                "🛡️ POST_HYPO_REBOUND: Rebond suspecté (${sinceHypoMs / 60_000}min depuis BG<70, COB=${aimiFmt1(cob)}g noMeal)\n",
            )
        }
    }
}
