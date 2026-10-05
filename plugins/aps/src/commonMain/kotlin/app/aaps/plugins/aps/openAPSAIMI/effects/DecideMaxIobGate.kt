package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopPhase
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiLoopTelemetry
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiSmbComparison
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import app.aaps.plugins.aps.openAPSAIMI.smb.computeMealHighIobDecision

/** Field reads of `runCoreDecisionMaxIobExceededTempBasalGate`, each at the line that uses it. */
internal interface AimiMaxIobGateState {
    fun studyExporter(): HormonitorStudyExporter?
    fun activityContext(): ActivityContext
    fun adaptiveMult(): Double
    fun withoutZeros(value: Double): String
}

/** `setTempBasal` on the max-IOB branches. The pump body stays Android. */
internal fun interface AimiMaxIobTempBasal {
    fun setTempBasal(
        rate: Double,
        durationMin: Int,
        profile: OapsProfileAimi,
        rT: RT,
        currenttemp: CurrentTemp,
        overrideSafetyLimits: Boolean,
        forceExact: Boolean,
        adaptiveMultiplier: Double,
    ): RT
}

internal sealed class AimiMaxIobGateStage {
    data class ReturnTempBasal(val rt: RT) : AimiMaxIobGateStage()
    data class ContinueSMBPath(
        val allowMealHighIob: Boolean,
        val mealHighIobDamping: Double,
    ) : AimiMaxIobGateStage()
}

/**
 * `runCoreDecisionMaxIobExceededTempBasalGate`.
 *
 * When IOB is over the ceiling and the meal-high-IOB relax is off, the tick sets a 30 minute
 * temporary basal (or keeps the current one) and returns. Otherwise it hands the SMB path the
 * relax flags. This function has no swallowed [Exception].
 */
internal fun decideMaxIobExceededTempBasal(
    profile: OapsProfileAimi,
    ctx: AimiTickContext,
    rT: RT,
    originalProfile: OapsProfileAimi,
    flatBGsDetected: Boolean,
    mealModeActive: Boolean,
    maxIobLimit: Double,
    safetyDecision: SafetyDecision,
    basal: Double,
    bg: Double,
    delta: Float,
    eventualBG: Double,
    targetBgSchedule: Double,
    loopIob: Double,
    texts: TextResolver,
    state: AimiMaxIobGateState,
    effects: AimiMaxIobTempBasal,
    comparison: AimiSmbComparison,
    decisionLog: AimiDecisionLog,
): AimiMaxIobGateStage {
    val mealHighIobDecision = computeMealHighIobDecision(
        mealModeActive,
        bg,
        delta.toDouble(),
        eventualBG,
        targetBgSchedule,
        loopIob,
        maxIobLimit
    )
    val allowMealHighIob = mealHighIobDecision.relax
    val mealHighIobDamping = mealHighIobDecision.damping
    AimiLoopTelemetry.enterPhase(AimiLoopPhase.CORE_DECISION, state.studyExporter())

    if (loopIob > maxIobLimit && !allowMealHighIob) {
        rT.reason.append(texts.gs(ApsStrings.reason_iob_max, AimiTickPolicyMath.round(loopIob, 2), AimiTickPolicyMath.round(maxIobLimit, 2)))
        val finalResult = if (delta < 0) {
            val floorRate = AimiTickPolicyMath.applyBasalFloor(
                0.0,
                profile.current_basal,
                safetyDecision,
                state.activityContext(),
                bg,
                delta.toDouble(),
                ctx.glucoseStatus.shortAvgDelta.toDouble(),
                eventualBG.toDouble(),
                mealModeActive,
                HypoThresholdMath.getLgsThresholdSafe(profile)
            )

            if (floorRate > 0.0) {
                rT.reason.append(texts.gs(ApsStrings.reason_bg_dropping_floor, delta, floorRate))
                effects.setTempBasal(floorRate, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, forceExact = false, adaptiveMultiplier = state.adaptiveMult())
            } else {
                rT.reason.append(texts.gs(ApsStrings.reason_bg_dropping, delta))
                effects.setTempBasal(0.0, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, forceExact = false, adaptiveMultiplier = state.adaptiveMult())
            }
        } else if (ctx.currentTemp.duration > 15 && (AimiTickPolicyMath.roundBasal(basal) == AimiTickPolicyMath.roundBasal(ctx.currentTemp.rate))) {
            rT.reason.append(", temp ${ctx.currentTemp.rate} ~ req ${state.withoutZeros(AimiTickPolicyMath.round(basal, 2))}U/hr. ")
            rT
        } else {
            val safeBasal = AimiTickPolicyMath.applyBasalFloor(
                basal,
                profile.current_basal,
                safetyDecision,
                state.activityContext(),
                bg,
                delta.toDouble(),
                ctx.glucoseStatus.shortAvgDelta.toDouble(),
                eventualBG.toDouble(),
                mealModeActive,
                HypoThresholdMath.getLgsThresholdSafe(profile)
            )
            rT.reason.append(texts.gs(ApsStrings.reason_set_temp_basal, AimiTickPolicyMath.round(safeBasal, 2)))
            effects.setTempBasal(safeBasal, 30, profile, rT, ctx.currentTemp, overrideSafetyLimits = false, forceExact = false, adaptiveMultiplier = state.adaptiveMult())
        }
        comparison.compare(
            aimiResult = finalResult,
            glucoseStatus = ctx.glucoseStatus,
            currentTemp = ctx.currentTemp,
            iobData = ctx.iobDataArray,
            profileAimi = originalProfile,
            autosens = ctx.autosensData,
            mealData = ctx.mealData,
            microBolusAllowed = ctx.microBolusAllowed,
            currentTime = ctx.currentTime,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = ctx.dynIsfMode
        )
        decisionLog.logDecisionFinal("MAX_IOB", finalResult, bg, delta)
        return AimiMaxIobGateStage.ReturnTempBasal(finalResult)
    }
    return AimiMaxIobGateStage.ContinueSMBPath(
        allowMealHighIob = allowMealHighIob,
        mealHighIobDamping = mealHighIobDamping,
    )
}
