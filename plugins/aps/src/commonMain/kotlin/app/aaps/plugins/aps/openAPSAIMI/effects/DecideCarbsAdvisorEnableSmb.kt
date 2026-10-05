package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefPredictionReasonSuffix
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalHistoryUtils
import app.aaps.plugins.aps.openAPSAIMI.carbs.CarbsAdvisor
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoTools
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision

internal data class AimiCarbsAdvisorEnableSmbResult(
    val forcedBasalmealmodes: Double,
    val forcedBasal: Double,
    val enableSMB: Boolean,
    val mealModeActive: Boolean,
    val zeroSinceMin: Int,
    val minutesSinceLastChange: Int,
    val safetyDecision: SafetyDecision,
)

/**
 * Android reads of the carbs hint, SMB enable, basal-zero history and hypo SMB factor.
 * Each method is the call that already existed at that line.
 * `enablesmb` is the Android shell of `decideEnableSmb`. `convertBG` and the phrase book stay Android.
 */
internal interface AimiCarbsAdvisorEnableSmbCalls {
    fun targetBg(): Double
    fun lunchTime(): Boolean
    fun dinnerTime(): Boolean
    fun bfastTime(): Boolean
    fun highCarbTime(): Boolean
    fun mealTime(): Boolean
    fun mealModesMaxBasal(): Double
    fun autodriveMaxBasal(): Double
    fun enableSmb(
        profile: OapsProfileAimi,
        microBolusAllowed: Boolean,
        mealData: MealData,
        targetBgSchedule: Double,
        mealModeActive: Boolean,
        currentBg: Double,
        delta: Double,
        eventualBg: Double,
        combinedDelta: Double,
    ): Boolean
    fun mealModeSmbReason(): String?
    fun reason(rT: RT, msg: String)
    fun withoutZeros(value: Double): String
    fun convertBg(value: Double): String
    fun appendAdditionalCarbs(rT: RT, carbsRequired: Int, minutesAboveThreshold: Int)
    fun writeZeroBasalAccumulated(minutes: Int)
    fun appendEventualBg(rT: RT, eventualBg: Double, maxBgSchedule: Double)
    fun tdd24h(): Double
    fun tirInHypo(): Double
    fun recentGlucose(): List<Float>
    fun tddPerHour(): Float
    fun fieldDelta(): Float
    fun honeymoon(): Boolean
    fun phraseBook(): AimiHypoSmbSafety.PhraseBook
    fun postHypoRisk()
}

internal fun decideCarbsAdvisorEnableSmbBasalHistoryAndSafety(
    profile: OapsProfileAimi,
    ctx: AimiTickContext,
    rT: RT,
    glucoseStatus: GlucoseStatusAIMI,
    iobData: IobTotal,
    csf: Double,
    slopeFromDeviations: Double,
    sens: Double,
    bg: Double,
    iob: Float,
    cob: Float,
    delta: Float,
    eventualBG: Double,
    combinedDelta: Float,
    deviation: Int,
    bgi: Double,
    targetBgSchedule: Double,
    maxBgSchedule: Double,
    windowSinceDoseInt: Int,
    calls: AimiCarbsAdvisorEnableSmbCalls,
): AimiCarbsAdvisorEnableSmbResult {
    val thresholdBG = 70.0
    val carbsRequired = CarbsAdvisor.estimateRequiredCarbs(
        bg = bg,
        targetBG = calls.targetBg(),
        slope = slopeFromDeviations,
        iob = iob.toDouble(),
        csf = csf,
        isf = sens,
        cob = cob.toDouble(),
    )
    val minutesAboveThreshold = HypoTools.calculateMinutesAboveThreshold(bg, slopeFromDeviations, thresholdBG)
    if (carbsRequired >= profile.carbsReqThreshold && minutesAboveThreshold <= 45 && !calls.lunchTime() && !calls.dinnerTime() && !calls.bfastTime() && !calls.highCarbTime() && !calls.mealTime()) {
        rT.carbsReq = carbsRequired
        rT.carbsReqWithin = minutesAboveThreshold
        calls.appendAdditionalCarbs(rT, carbsRequired, minutesAboveThreshold)
    }

    val forcedBasalmealmodes = calls.mealModesMaxBasal()
    val forcedBasal = calls.autodriveMaxBasal()

    val mealModeActive = calls.mealTime() || calls.bfastTime() || calls.lunchTime() || calls.dinnerTime() || calls.highCarbTime()

    val enableSMB = calls.enableSmb(
        profile,
        ctx.microBolusAllowed,
        ctx.mealData,
        targetBgSchedule,
        mealModeActive,
        bg,
        delta.toDouble(),
        eventualBG,
        combinedDelta.toDouble(),
    )

    calls.mealModeSmbReason()?.let { calls.reason(rT, it) }

    rT.COB = ctx.mealData.mealCOB
    rT.IOB = iobData.iob
    rT.reason.append(
        "COB: ${AimiTickPolicyMath.round(ctx.mealData.mealCOB, 1).let(calls::withoutZeros)}, Dev: ${calls.convertBg(deviation.toDouble())}, BGI: ${calls.convertBg(bgi)}, ISF: ${calls.convertBg(sens)}, CR: ${
            AimiTickPolicyMath.round(profile.carb_ratio, 2)
                .let(calls::withoutZeros)
        }, Target: ${calls.convertBg(targetBgSchedule)}${
            OrefPredictionReasonSuffix.build(rT) { v -> calls.convertBg(v) }
        } \uD83D\uDCD2 "
    )
    val zeroSinceMin = BasalHistoryUtils.historyProvider.zeroBasalDurationMinutes(2)
    val minutesSinceLastChange = BasalHistoryUtils.historyProvider.minutesSinceLastChange()
    calls.writeZeroBasalAccumulated(zeroSinceMin)
    if (eventualBG >= maxBgSchedule) {
        calls.appendEventualBg(rT, eventualBG, maxBgSchedule)
    }
    val tdd24h = calls.tdd24h()
    val tirInHypo = calls.tirInHypo()
    val safetyDecision = AimiHypoSmbSafety.safetyAdjustment(
        currentBG = glucoseStatus.glucose.toFloat(),
        predictedBG = eventualBG.toFloat(),
        bgHistory = calls.recentGlucose(),
        combinedDelta = combinedDelta,
        iob = iob,
        maxIob = profile.max_iob.toFloat(),
        tdd24Hrs = tdd24h.toFloat(),
        tddPerHour = calls.tddPerHour(),
        tirInhypo = tirInHypo.toFloat(),
        targetBG = profile.target_bg.toFloat(),
        zeroBasalDurationMinutes = windowSinceDoseInt,
        delta = calls.fieldDelta(),
        honeymoon = calls.honeymoon(),
        phrase = calls.phraseBook(),
    )
    rT.isHypoRisk = safetyDecision.isHypoRisk

    if (safetyDecision.isHypoRisk) {
        calls.postHypoRisk()
    }

    return AimiCarbsAdvisorEnableSmbResult(
        forcedBasalmealmodes = forcedBasalmealmodes,
        forcedBasal = forcedBasal,
        enableSMB = enableSMB,
        mealModeActive = mealModeActive,
        zeroSinceMin = zeroSinceMin,
        minutesSinceLastChange = minutesSinceLastChange,
        safetyDecision = safetyDecision,
    )
}
