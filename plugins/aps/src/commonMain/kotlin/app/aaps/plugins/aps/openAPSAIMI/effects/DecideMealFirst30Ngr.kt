package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.NGRConfig
import app.aaps.plugins.aps.openAPSAIMI.NGRResult
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Meal flags and runtimes of `runPostSafetyMealFirst30NgrHeadroomBasalSmbStage`,
 * read at the line that uses them.
 */
internal interface AimiMealFirstNgrState {
    fun mealTime(): Boolean
    fun mealRuntime(): Long
    fun bfastTime(): Boolean
    fun bfastRuntime(): Long
    fun lunchTime(): Boolean
    fun lunchRuntime(): Long
    fun dinnerTime(): Boolean
    fun dinnerRuntime(): Long
    fun highCarbTime(): Boolean
    fun highCarbRuntime(): Long
    fun adaptiveMult(): Double
    fun maxSMB(): Double
    fun setMaxIob(value: Double)
}

/** `setTempBasal` at the meal first-30 line. The pump body stays Android. */
internal fun interface AimiMealFirstTempBasal {
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

/** `nightGrowthResistanceMode.evaluate` at the line the reference calls it. */
internal fun interface AimiNightGrowthEvaluate {
    fun evaluate(
        now: Instant,
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        eventualBG: Double,
        targetBG: Double,
        iob: Double,
        cob: Double,
        react: Double,
        isMealActive: Boolean,
        config: NGRConfig,
    ): NGRResult
}

internal sealed class AimiMealFirstNgrStage {
    data class EarlyTempBasal(val rt: RT) : AimiMealFirstNgrStage()
    data class Continue(
        val isMealActive: Boolean,
        val runtimeMinValue: Int,
        val maxIobLimit: Double,
        val basal: Double,
        val smbToGive: Float,
    ) : AimiMealFirstNgrStage()
}

/**
 * `runPostSafetyMealFirst30NgrHeadroomBasalSmbStage`.
 *
 * A meal mode inside its first 30 minutes forces a temporary basal unless that rate is
 * already running. Otherwise NGR may raise the IOB ceiling and boost basal and SMB.
 * This function has no swallowed [Exception].
 */
internal fun decideMealFirst30NgrHeadroomBasalSmb(
    profile: OapsProfileAimi,
    ctx: AimiTickContext,
    rT: RT,
    ngrConfig: NGRConfig,
    safetyDecision: SafetyDecision,
    forcedBasalmealmodes: Double,
    maxIobLimitIn: Double,
    basalIn: Double,
    smbToGiveIn: Float,
    bg: Double,
    delta: Float,
    shortAvgDelta: Float,
    longAvgDelta: Float,
    eventualBG: Double,
    targetBgSchedule: Double,
    preferences: Preferences,
    texts: TextResolver,
    consoleLog: MutableList<String>,
    state: AimiMealFirstNgrState,
    effects: AimiMealFirstTempBasal,
    ngr: AimiNightGrowthEvaluate,
): AimiMealFirstNgrStage {
    val (isMealActive, runtimeMinLabel, runtimeMinValue) = when {
        state.mealTime() -> Triple(true, "meal", AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(state.mealRuntime()))
        state.bfastTime() -> Triple(true, "bfast", AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(state.bfastRuntime()))
        state.lunchTime() -> Triple(true, "lunch", AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(state.lunchRuntime()))
        state.dinnerTime() -> Triple(true, "dinner", AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(state.dinnerRuntime()))
        state.highCarbTime() -> Triple(true, "highcarb", AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(state.highCarbRuntime()))
        else -> Triple(false, "", Int.MAX_VALUE)
    }

    if (isMealActive && runtimeMinValue in 0..30) {
        val forced = forcedBasalmealmodes.coerceAtLeast(0.05)
        val alreadyForced = abs(ctx.currentTemp.rate - forced) < 0.05 && ctx.currentTemp.duration >= 25
        if (!alreadyForced) {
            rT.reason.append(
                texts.gs(
                    ApsStrings.meal_mode_first_30,
                    "$runtimeMinLabel($runtimeMinValue)",
                    forced
                )
            )
            return AimiMealFirstNgrStage.EarlyTempBasal(
                effects.setTempBasal(
                    forced, 30, profile, rT, ctx.currentTemp,
                    overrideSafetyLimits = true,
                    forceExact = false,
                    adaptiveMultiplier = state.adaptiveMult()
                )
            )
        }
    }

    val systemTime = ctx.currentTime
    val iobTotal = ctx.iobDataArray[0]
    val ngrResult = ngr.evaluate(
        now = Instant.fromEpochMilliseconds(systemTime),
        bg = bg,
        delta = delta.toDouble(),
        shortAvgDelta = shortAvgDelta.toDouble(),
        longAvgDelta = longAvgDelta.toDouble(),
        eventualBG = eventualBG,
        targetBG = targetBgSchedule,
        iob = iobTotal.iob,
        cob = ctx.mealData.mealCOB,
        react = bg,
        isMealActive = isMealActive,
        config = ngrConfig
    )
    if (ngrResult.reason.isNotEmpty()) {
        rT.reason.appendLine(ngrResult.reason)
        consoleLog.add(ngrResult.reason)
    }
    val lowTempTarget = profile.temptargetSet && targetBgSchedule <= profile.target_bg
    var maxIobLimit = maxIobLimitIn
    val originalMaxIobLimit = maxIobLimit
    if (!lowTempTarget && ngrResult.extraIOBHeadroomU > 0.0) {
        val slotBudget = ngrConfig.extraIobPer30Min * ngrConfig.headroomSlotCap
        val absoluteMaxIob = preferences.get(DoubleKey.ApsSmbMaxIob) + slotBudget
        val candidate = maxIobLimit + ngrResult.extraIOBHeadroomU
        val updatedLimit = min(candidate, absoluteMaxIob)
        if (updatedLimit > originalMaxIobLimit + 0.01) {
            maxIobLimit = updatedLimit
            state.setMaxIob(maxIobLimit)
            val headroomMessage = texts.gs(
                ApsStrings.oaps_aimi_ngr_headroom,
                AimiTickPolicyMath.round(maxIobLimit - originalMaxIobLimit, 2),
                AimiTickPolicyMath.round(maxIobLimit, 2)
            )
            rT.reason.appendLine(headroomMessage)
            consoleLog.add(headroomMessage)
        }
    }
    state.setMaxIob(maxIobLimit)
    val safeBgThreshold = max(110.0, targetBgSchedule)
    var basal = basalIn
    val originalBasal = basal
    val shouldApplyBasalBoost = ngrResult.basalMultiplier > 1.0001 && !lowTempTarget && delta > 0 && shortAvgDelta > 0 && bg > targetBgSchedule
    if (shouldApplyBasalBoost && originalBasal > 0.0) {
        val boostedBasal = AimiTickPolicyMath.roundBasal((originalBasal * ngrResult.basalMultiplier).coerceAtLeast(0.05))
        if (boostedBasal > originalBasal + 0.01) {
            basal = boostedBasal
            val basalMessage = texts.gs(
                ApsStrings.oaps_aimi_ngr_basal_applied,
                boostedBasal / originalBasal,
                AimiTickPolicyMath.round(boostedBasal, 2)
            )
            rT.reason.appendLine(basalMessage)
            consoleLog.add(basalMessage)
        }
    }
    var smbToGive = smbToGiveIn
    val originalSmb = smbToGive.toDouble()
    val shouldApplySmbBoost = ngrResult.smbMultiplier > 1.0001 && !lowTempTarget && safetyDecision.bolusFactor >= 1.0 && eventualBG > targetBgSchedule && delta > 0 && bg >= safeBgThreshold
    if (shouldApplySmbBoost && originalSmb > 0.0) {
        val boosted = originalSmb * ngrResult.smbMultiplier
        val smbClamp = min(ngrConfig.maxSMBClampU, state.maxSMB())
        val finalSmb = boosted.coerceAtMost(smbClamp)
        val appliedMultiplier = finalSmb / originalSmb
        if (appliedMultiplier > 1.0001) {
            smbToGive = finalSmb.toFloat()
            val smbMessage = texts.gs(
                ApsStrings.oaps_aimi_ngr_smb_applied,
                appliedMultiplier,
                AimiTickPolicyMath.round(finalSmb, 3),
                AimiTickPolicyMath.round(smbClamp, 3)
            )
            rT.reason.appendLine(smbMessage)
            consoleLog.add(smbMessage)
        }
    }
    return AimiMealFirstNgrStage.Continue(
        isMealActive = isMealActive,
        runtimeMinValue = runtimeMinValue,
        maxIobLimit = maxIobLimit,
        basal = basal,
        smbToGive = smbToGive,
    )
}
