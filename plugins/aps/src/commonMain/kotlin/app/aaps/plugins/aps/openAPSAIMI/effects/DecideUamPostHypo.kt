package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskEnvelope
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate

/**
 * Android reads of the UAM SMB, the hypo hysteresis and the post-hypo bridge.
 * Each method is the call that already existed at that line. The TFLite model stays Android.
 */
internal interface AimiUamPostHypoCalls {
    fun modelSmb(reason: StringBuilder): Float
    fun riskEnvelope(): AimiRiskEnvelope?
    fun sanitizedPredictedEventual(rT: RT, predictedBg: Double, eventualBg: Double): Pair<Double, Double>
    fun nowMs(): Long
    fun hypoState(): AimiHypoSmbSafety.HypoHysteresisState
    fun writeHypoState(state: AimiHypoSmbSafety.HypoHysteresisState)
    fun aggression(): CorrectionAggressionGate.Decision?
    fun clearHypoBlockAt()
    fun appendHypoGuard(
        rT: RT,
        composite: Double,
        hypoThreshold: Double,
        bg: Double,
        predicted: Double,
        eventual: Double,
    )
    fun setPredictedSmb(value: Float)
    fun maxSmb(): Double
}

internal fun decideUamPostHypoSmb(
    rT: RT,
    bg: Double,
    delta: Float,
    iob: Float,
    predictedBg: Float,
    eventualBg: Double,
    threshold: Double,
    minBgHypoComposite: Double,
    targetBg: Double,
    profile: OapsProfileAimi,
    postHypoState: PostHypoState,
    cob: Float,
    consoleLog: MutableList<String>,
    calls: AimiUamPostHypoCalls,
): Float {
    val modelcal = calls.modelSmb(rT.reason)
    val decisionRisk = calls.riskEnvelope()
    val hypoThresholdForGuard = decisionRisk?.hypoThresholdMgdl ?: threshold
    val hypoCompositeForReason = decisionRisk?.compositeMinMgdl ?: minBgHypoComposite
    val (hypoPredSanitized, hypoEventualSanitized) = calls.sanitizedPredictedEventual(
        rT = rT,
        predictedBg = predictedBg.toDouble(),
        eventualBg = eventualBg,
    )
    val step = AimiHypoSmbSafety.stepHypoHysteresis(
        bg = bg,
        predictedBg = hypoPredSanitized,
        eventualBg = hypoEventualSanitized,
        threshold = hypoThresholdForGuard,
        deltaMgdlPer5min = delta.toDouble(),
        now = calls.nowMs(),
        state = calls.hypoState(),
    )
    calls.writeHypoState(step.state)
    var isHypoBlocked = step.blocked

    val aggression = calls.aggression()
    if (isHypoBlocked && aggression?.allowRocketHypoOverride == true) {
        isHypoBlocked = false
        calls.clearHypoBlockAt()
        rT.reason.append(
            "🚀 Rocket Override (${CorrectionAggressionGate.LOG_PREFIX} ${aggression.tier.name}): Hypo Block IGNORED. "
        )
    } else if (isHypoBlocked && (delta > 5.0 || bg > targetBg + 40)) {
        consoleLog.add(
            "${CorrectionAggressionGate.LOG_PREFIX}: hypo rocket override BLOCKED " +
                "(tier=${aggression?.tier?.name ?: "n/a"} tag=${aggression?.reasonTag ?: "n/a"})"
        )
    }

    var fallbackActive = false
    if (isHypoBlocked) {
        if (AimiTickPolicyMath.canFallbackSmbWithoutPrediction(bg, delta.toDouble(), targetBg, iob.toDouble(), profile)) {
            fallbackActive = true
        }
    }

    if (isHypoBlocked && !fallbackActive) {
        calls.appendHypoGuard(
            rT,
            hypoCompositeForReason,
            hypoThresholdForGuard,
            bg,
            predictedBg.toDouble(),
            eventualBg,
        )
        calls.setPredictedSmb(0f)
    } else {
        var finalModelSmb = modelcal

        if (fallbackActive) {
            finalModelSmb = modelcal * 0.5f
            rT.reason.appendLine(
                "Hyper fallback active: SMB unblocked (50% damped) despite missing prediction. UAM: ${aimiFmt2(modelcal)} -> ${aimiFmt2(finalModelSmb)}"
            )
        } else {
            rT.reason.appendLine("💉 SMB (UAM): ${aimiFmt2(modelcal)} U")
        }

        when (postHypoState) {
            is PostHypoState.ReboundSuspected -> {
                val bridgeTbr = (profile.current_basal * 2.0)
                    .coerceAtMost(profile.max_basal * 0.35)
                finalModelSmb = 0f
                rT.rate = bridgeTbr
                rT.duration = 5
                consoleLog.add(
                    "🛡️ POST_HYPO_REBOUND: SMB=0 → TBR bridge ${aimiFmt2(bridgeTbr)} U/h " +
                        "(${postHypoState.sinceMs / 60_000}min depuis BG<70, COB=${aimiFmt1(cob)}g)"
                )
            }
            is PostHypoState.MealConfirmed -> {
                val maxSmbPref = calls.maxSmb().toFloat()
                finalModelSmb = (finalModelSmb * 0.5f).coerceAtMost(maxSmbPref * 0.5f)
                consoleLog.add(
                    "🍽️ POST_HYPO_MEAL: SMB capped 50% → ${aimiFmt2(finalModelSmb)} U " +
                        "(COB=${aimiFmt1(cob)}g, ${postHypoState.sinceMs / 60_000}min post-hypo)"
                )
            }
            PostHypoState.None -> Unit
        }

        calls.setPredictedSmb(finalModelSmb)
    }
    return modelcal
}
