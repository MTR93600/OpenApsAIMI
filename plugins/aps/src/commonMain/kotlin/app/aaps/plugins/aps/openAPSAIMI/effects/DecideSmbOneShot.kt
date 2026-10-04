package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import kotlin.math.max

internal data class AimiSmbExecution(
    val predictedSmb: Float,
    val basal: Double,
    val finalSmb: Float,
    val highBgOverrideUsed: Boolean,
    val newSmbInterval: Int?,
)

/**
 * Android reads and writes of [decideSmbAdvisorOneShot], each at the line that uses it.
 * The legacy blender stays Android and runs only when it is not skipped.
 */
internal interface AimiSmbOneShotCalls {
    fun setMealAdvisorOneShot(value: Boolean)
    fun maxSmb(): Double
    fun setMaxSmb(value: Double)
    fun maxSmbHb(): Double
    fun setMaxSmbHb(value: Double)
    fun predictedSmb(): Float
    fun logSmbDecision(
        bg: Double,
        delta: Float,
        iob: Float,
        hasPred: Boolean,
        hyperKicker: Boolean,
        modelCal: Float,
        proposed: Float,
    )
    fun authoritativePhrase(): String
    fun executeLegacy(
        bg: Double,
        delta: Float,
        iob: Float,
        basalAimi: Float,
        basal: Double,
        honeymoon: Boolean,
        hourOfDay: Int,
        mealTime: Boolean,
        bfastTime: Boolean,
        lunchTime: Boolean,
        dinnerTime: Boolean,
        highCarbTime: Boolean,
        snackTime: Boolean,
        sens: Double,
        tp: Float,
        variableSensitivity: Float,
        targetBg: Double,
        predictedBg: Float,
        eventualBg: Double,
        isMealAdvisorOneShot: Boolean,
        mealData: MealData,
        pkpdRuntime: PkPdRuntime?,
        sportTime: Boolean,
        lateFatRiseFlag: Boolean,
        highCarbRuntime: Long,
        threshold: Double,
        currentTime: Long,
        windowSinceDoseInt: Int,
        intervalSmb: Int,
        insulinStep: Float,
        highBgOverrideUsed: Boolean,
        cob: Float,
        pkpdDiaMinutesOverride: Double?,
        profile: OapsProfileAimi,
        rT: RT,
        combinedDelta: Float,
        glucoseStatus: GlucoseStatusAIMI,
        pumpAgeDays: Float,
        modelCal: Double,
        profileCurrentBasal: Double,
        isConfirmedHighRise: Boolean,
        exerciseInsulinLockout: Boolean,
        minBgLookbackMgdl: Double,
    ): AimiSmbExecution
}

internal data class AimiSmbOneShotStage(
    val smbExecution: AimiSmbExecution,
    val isMealAdvisorOneShot: Boolean,
)

/**
 * `runSmbDecisionLogAdvisorOneShotAndExecuteInstruction`.
 *
 * An advisor trigger lifts the SMB ceiling to at least 30 U. When the legacy blender is skipped,
 * the delivered SMB is the V3 request. In the locked scene that request is 1.50 U.
 * This function has no swallowed [Exception].
 */
internal fun decideSmbAdvisorOneShot(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    glucoseStatus: GlucoseStatusAIMI,
    bg: Double,
    delta: Float,
    iob: Float,
    shortAvgDelta: Float,
    predictedBg: Float,
    eventualBg: Double,
    sens: Double,
    tp: Double,
    variableSensitivity: Float,
    targetBg: Double,
    basalAimi: Float,
    basal: Double,
    honeymoon: Boolean,
    hourOfDay: Int,
    mealTime: Boolean,
    bfastTime: Boolean,
    lunchTime: Boolean,
    dinnerTime: Boolean,
    highCarbTime: Boolean,
    snackTime: Boolean,
    sportTime: Boolean,
    lateFatRiseFlag: Boolean,
    highCarbRuntime: Long,
    threshold: Double,
    windowSinceDoseInt: Int,
    intervalSmb: Int,
    insulinStep: Float,
    highBgOverrideUsed: Boolean,
    cob: Float,
    pkpdRuntime: PkPdRuntime?,
    pumpAgeDays: Float,
    modelCal: Float,
    profileCurrentBasal: Double,
    isConfirmedHighRise: Boolean,
    exerciseInsulinLockout: Boolean,
    combinedDelta: Float,
    skipLegacySmbBlender: Boolean,
    minBgLookbackMgdl: Double,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiSmbOneShotCalls,
): AimiSmbOneShotStage {
    val hasPred = predictedBg > 20
    val hyperKicker = (bg > targetBg + 30 && (delta >= 0.3 || shortAvgDelta >= 0.2))

    val isMealAdvisorOneShot = preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)
    calls.setMealAdvisorOneShot(isMealAdvisorOneShot)
    val advisorMealPriority = bg > SEVERE_HYPO_MEAL_OVERRIDE_MGDL
    if (isMealAdvisorOneShot && (!exerciseInsulinLockout || advisorMealPriority)) {
        preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, false)

        calls.setMaxSmb(max(calls.maxSmb(), 30.0))
        calls.setMaxSmbHb(max(calls.maxSmbHb(), 30.0))

        if (exerciseInsulinLockout) {
            consoleLog.add("🚀 MEAL ADVISOR ONE-SHOT: priorité repas — lockout exercice/activité contourné. MaxSMB raised to 30U.")
        } else {
            consoleLog.add("🚀 MEAL ADVISOR ONE-SHOT: Forcing Aggression. MaxSMB raised to 30U.")
        }
        rT.reason.append("🚀 Advisor Trigger: MaxSMB Bypass Active. ")
    } else if (isMealAdvisorOneShot && exerciseInsulinLockout) {
        preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, false)
        consoleLog.add("🚀 MEAL ADVISOR ONE-SHOT ignoré (hypo sévère sous sport / contexte activité).")
        rT.reason.append("🚀 Advisor Trigger ignoré (hypo sévère / exercice). ")
    }

    calls.logSmbDecision(bg, delta, iob, hasPred, hyperKicker, modelCal, calls.predictedSmb())
    val pkpdDiaMinutesOverride: Double? = pkpdRuntime?.params?.diaHrs?.let { it * 60.0 }

    val smbExecution = if (skipLegacySmbBlender) {
        val v3SmbUnits = (rT.insulinReq ?: 0.0).coerceAtLeast(0.0)
        rT.reason.appendLine(calls.authoritativePhrase())
        consoleLog.add(
            "AUTODRIVE_V3_AUTHORITATIVE: SMB ${aimiFmt2(v3SmbUnits)} U from V3 (legacy blender skipped)"
        )
        AimiSmbExecution(
            predictedSmb = calls.predictedSmb(),
            basal = basal,
            finalSmb = v3SmbUnits.toFloat(),
            highBgOverrideUsed = highBgOverrideUsed,
            newSmbInterval = intervalSmb,
        )
    } else {
        calls.executeLegacy(
            bg = bg,
            delta = delta,
            iob = iob,
            basalAimi = basalAimi,
            basal = basal,
            honeymoon = honeymoon,
            hourOfDay = hourOfDay,
            mealTime = mealTime,
            bfastTime = bfastTime,
            lunchTime = lunchTime,
            dinnerTime = dinnerTime,
            highCarbTime = highCarbTime,
            snackTime = snackTime,
            sens = sens,
            tp = tp.toFloat(),
            variableSensitivity = variableSensitivity,
            targetBg = targetBg,
            predictedBg = predictedBg,
            eventualBg = eventualBg,
            isMealAdvisorOneShot = isMealAdvisorOneShot,
            mealData = ctx.mealData,
            pkpdRuntime = pkpdRuntime,
            sportTime = sportTime,
            lateFatRiseFlag = lateFatRiseFlag,
            highCarbRuntime = highCarbRuntime,
            threshold = threshold,
            currentTime = ctx.currentTime,
            windowSinceDoseInt = windowSinceDoseInt,
            intervalSmb = intervalSmb,
            insulinStep = insulinStep,
            highBgOverrideUsed = highBgOverrideUsed,
            cob = cob,
            pkpdDiaMinutesOverride = pkpdDiaMinutesOverride,
            profile = profile,
            rT = rT,
            combinedDelta = combinedDelta,
            glucoseStatus = glucoseStatus,
            pumpAgeDays = pumpAgeDays,
            modelCal = modelCal.toDouble(),
            profileCurrentBasal = profileCurrentBasal,
            isConfirmedHighRise = isConfirmedHighRise,
            exerciseInsulinLockout = exerciseInsulinLockout,
            minBgLookbackMgdl = minBgLookbackMgdl,
        )
    }

    return AimiSmbOneShotStage(
        smbExecution = smbExecution,
        isMealAdvisorOneShot = isMealAdvisorOneShot,
    )
}
