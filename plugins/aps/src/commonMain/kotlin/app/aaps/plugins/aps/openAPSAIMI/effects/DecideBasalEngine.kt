package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import kotlin.math.abs

/**
 * Android reads of the basal-engine stage.
 * Each method is the call that already existed at that line.
 * `calculateRate` and `detectMealOnset` stay Android.
 */
internal interface AimiBasalDecisionEngineCalls {
    fun snackTime(): Boolean
    fun snackRuntime(): Long
    fun fastingTime(): Boolean
    fun sportTime(): Boolean
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
    fun recentSteps5Minutes(): Int
    fun calculateRate(
        basal: Double,
        currentBasal: Double,
        multiplier: Double,
        reason: String,
        currentTemp: CurrentTemp,
        rT: RT,
    ): Double
    fun detectMealOnset(
        delta: Float,
        predictedDelta: Float,
        acceleration: Float,
        predictedBg: Float,
        targetBg: Float,
    ): Boolean
    fun engine(): BasalDecisionEngine
}

internal fun decideBasalDecisionEngine(
    currentTemp: CurrentTemp,
    mealData: MealData,
    profile: OapsProfileAimi,
    rT: RT,
    glucoseStatus: GlucoseStatusAIMI,
    featuresCombinedDelta: Double?,
    profileCurrentBasal: Double,
    basalEstimate: Double,
    tdd7P: Double,
    tdd7Days: Double,
    variableSensitivity: Double,
    predictedBg: Double,
    targetBg: Double,
    tickIobForEngine: Double,
    engineMaxIob: Double,
    eventualBg: Double,
    bg: Double,
    delta: Double,
    shortAvgDelta: Double,
    longAvgDelta: Double,
    combinedDelta: Double,
    bgAcceleration: Double,
    allowMealHighIob: Boolean,
    safetyDecision: SafetyDecision,
    forcedBasal: Double,
    forcedBasalMealModesMax: Double,
    isMealActive: Boolean,
    runtimeMinValue: Int,
    smbToGive: Double,
    zeroSinceMin: Int,
    minutesSinceLastChange: Int,
    pumpCaps: PumpCaps,
    timenowHour: Int,
    sixAmHour: Int,
    pregnancyEnable: Boolean,
    nightMode: Boolean,
    modesCondition: Boolean,
    autodrivePref: Boolean,
    honeymoon: Boolean,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiBasalDecisionEngineCalls,
): BasalDecisionEngine.Decision {
    val forcedMealActive =
        abs(currentTemp.rate - forcedBasalMealModesMax) < 0.05 && currentTemp.duration > 0
    val auditorConfidence =
        try {
            AuditorVerdictCache.get(300_000)?.verdict?.confidence
        } catch (e: Exception) {
            consoleLog.add(
                "Auditor verdict failed (${e::class.simpleName}): ${e.message} — confidence 0",
            )
            0.0
        } ?: 0.0
    val basalInput = BasalDecisionEngine.Input(
        bg = bg,
        profileCurrentBasal = profileCurrentBasal,
        basalEstimate = basalEstimate,
        tdd7P = tdd7P,
        tdd7Days = tdd7Days,
        variableSensitivity = variableSensitivity,
        profileSens = profile.sens,
        // Carried on the profile of THIS tick, never read from a diagnostic global: `profile.sens`
        // is the commanded value and carries both floors, and a process-global would hand this
        // basal a value captured at another time of day.
        preFloorCommandedSens = profile.pre_floor_isf_mgdl,
        predictedBg = predictedBg,
        targetBg = targetBg,
        minBg = profile.min_bg,
        lgsThreshold = HypoThresholdMath.getLgsThresholdSafe(profile),
        eventualBg = eventualBg,
        iob = tickIobForEngine,
        maxIob = engineMaxIob,
        allowMealHighIob = allowMealHighIob,
        safetyDecision = safetyDecision,
        mealData = mealData,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        longAvgDelta = longAvgDelta,
        combinedDelta = combinedDelta,
        bgAcceleration = bgAcceleration,
        slopeFromMaxDeviation = mealData.slopeFromMaxDeviation,
        slopeFromMinDeviation = mealData.slopeFromMinDeviation,
        forcedBasal = forcedBasal,
        forcedMealActive = forcedMealActive,
        isMealActive = isMealActive,
        runtimeMinValue = runtimeMinValue,
        snackTime = calls.snackTime(),
        snackRuntimeMin = AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(calls.snackRuntime()),
        fastingTime = calls.fastingTime(),
        sportTime = calls.sportTime(),
        honeymoon = honeymoon,
        pregnancyEnable = pregnancyEnable,
        mealTime = calls.mealTime(),
        mealRuntimeMin = AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(calls.mealRuntime()),
        bfastTime = calls.bfastTime(),
        bfastRuntimeMin = AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(calls.bfastRuntime()),
        lunchTime = calls.lunchTime(),
        lunchRuntimeMin = AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(calls.lunchRuntime()),
        dinnerTime = calls.dinnerTime(),
        dinnerRuntimeMin = AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(calls.dinnerRuntime()),
        highCarbTime = calls.highCarbTime(),
        highCarbRuntimeMin = AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(calls.highCarbRuntime()),
        timenow = timenowHour,
        sixAmHour = sixAmHour,
        recentSteps5Minutes = calls.recentSteps5Minutes(),
        nightMode = nightMode,
        modesCondition = modesCondition,
        autodrive = autodrivePref,
        currentTemp = currentTemp,
        glucoseStatus = glucoseStatus,
        featuresCombinedDelta = featuresCombinedDelta,
        smbToGive = smbToGive,
        zeroSinceMin = zeroSinceMin,
        minutesSinceLastChange = minutesSinceLastChange,
        pumpCaps = pumpCaps,
        auditorConfidence = auditorConfidence,
        projectionHorizonMin = DynamicBasalController.PROJECTION_HORIZON_MIN
            .takeIf { preferences.get(BooleanKey.OApsAIMIBasalProjectedError) },
    )
    val helpers = BasalDecisionEngine.Helpers(
        calculateRate = { basalValue, currentBasalValue, multiplier, label ->
            calls.calculateRate(basalValue, currentBasalValue, multiplier, label, currentTemp, rT)
        },
        calculateBasalRate = { basalValue, currentBasalValue, multiplier ->
            AimiTickPolicyMath.calculateBasalRate(basalValue, currentBasalValue, multiplier)
        },
        detectMealOnset = { deltaValue, predictedDelta, acceleration, predBg, targBg ->
            calls.detectMealOnset(deltaValue, predictedDelta, acceleration, predBg, targBg)
        },
        round = { value, digits -> AimiTickPolicyMath.round(value, digits) },
    )
    return calls.engine().decide(basalInput, rT, helpers)
}
