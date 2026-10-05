package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aimicontracts.AimiDecisionTrace
import app.aaps.plugins.aimicontracts.AimiEngineState
import app.aaps.plugins.aimicontracts.AimiSafetyReport
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimicontracts.AimiTickResult
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalHistoryUtils
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalPlanner
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision

/**
 * Sport tick of the locked basal-engine scene.
 *
 * Acceleration 0 keeps the sport branch at 1.30 U/h. Acceleration 2, with modes and autodrive
 * and no effort assessment, selects [forcedBasal] 2.0 and appends
 * `phrase [AD_EARLY_TBR_TRIGGER rate=2.0]`. The text resolver returns `phrase`, the same stand-in
 * the Android shell uses for every `gs`. Empty basal history makes [BasalPlanner.plan] return null
 * on BG 180 / delta +5, so the planner does not return before the forced branch.
 */
internal fun iosNeutralSportBasal(
    state: AimiEngineState,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    onsetDelta: Float,
    onsetAcceleration: Float,
    onsetPredictedBg: Float,
    onsetTargetBg: Float,
    onsetAssessment: EffortActivityBelief.Assessment?,
    onsetDeclaredMeal: Boolean,
    onsetCobG: Double,
): AimiTickResult {
    BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
    val adaptive = AIMIAdaptiveBasal(SportBasalNoOpLogger, SportBasalFormatter)
    val engine = BasalDecisionEngine(
        rh = SportBasalPhrase,
        aimiAdaptiveBasal = adaptive,
        basalPlanner = BasalPlanner(adaptive, SportBasalNoOpLogger),
    )
    val profile = virtualCobCurveProfile(carbRatio = 0.0).also {
        it.min_bg = 80.0
        it.pre_floor_isf_mgdl = 50.0
    }
    val currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0)
    val meal = MealData(mealCOB = 0.0)
    val rT = RT(runningDynamicIsf = false)
    val decision = decideBasalDecisionEngine(
        currentTemp = currentTemp,
        mealData = meal,
        profile = profile,
        rT = rT,
        glucoseStatus = GlucoseStatusAIMI(
            glucose = 180.0,
            delta = onsetDelta.toDouble(),
            date = aimiWallClockMs(),
        ),
        featuresCombinedDelta = 5.0,
        profileCurrentBasal = 1.0,
        basalEstimate = 1.0,
        tdd7P = 35.0,
        tdd7Days = 35.0,
        variableSensitivity = 50.0,
        predictedBg = onsetPredictedBg.toDouble(),
        targetBg = onsetTargetBg.toDouble(),
        tickIobForEngine = 1.0,
        engineMaxIob = 10.0,
        eventualBg = 180.0,
        bg = 180.0,
        delta = onsetDelta.toDouble(),
        shortAvgDelta = 5.0,
        longAvgDelta = 5.0,
        combinedDelta = 5.0,
        bgAcceleration = onsetAcceleration.toDouble(),
        allowMealHighIob = false,
        safetyDecision = SafetyDecision(
            stopBasal = false,
            bolusFactor = 1.0,
            reason = "",
            basalLS = false,
        ),
        forcedBasal = 2.0,
        forcedBasalMealModesMax = 0.0,
        isMealActive = false,
        runtimeMinValue = 0,
        smbToGive = 0.0,
        zeroSinceMin = 0,
        minutesSinceLastChange = 0,
        pumpCaps = PumpCaps(
            basalStep = 0.05,
            bolusStep = 0.05,
            minDurationMin = 30,
            maxBasal = 3.0,
            maxSmb = 1.0,
        ),
        timenowHour = 12,
        sixAmHour = 6,
        pregnancyEnable = false,
        nightMode = false,
        modesCondition = true,
        autodrivePref = true,
        honeymoon = false,
        preferences = preferences,
        consoleLog = consoleLog,
        calls = object : AimiBasalDecisionEngineCalls {
            override fun snackTime() = false
            override fun snackRuntime() = 0L
            override fun fastingTime() = false
            override fun sportTime() = true
            override fun mealTime() = false
            override fun mealRuntime() = 0L
            override fun bfastTime() = false
            override fun bfastRuntime() = 0L
            override fun lunchTime() = false
            override fun lunchRuntime() = 0L
            override fun dinnerTime() = false
            override fun dinnerRuntime() = 0L
            override fun highCarbTime() = false
            override fun highCarbRuntime() = 0L
            override fun recentSteps5Minutes() = 0
            override fun calculateRate(
                basal: Double,
                currentBasal: Double,
                multiplier: Double,
                reason: String,
                currentTemp: CurrentTemp,
                rT: RT,
            ) = decideCalculateRate(
                basal = basal,
                currentBasal = currentBasal,
                multiplier = multiplier,
                reason = reason,
                currentTemp = currentTemp,
                rT = rT,
                overrideSafety = false,
            )

            override fun detectMealOnset(
                delta: Float,
                predictedDelta: Float,
                acceleration: Float,
                predictedBg: Float,
                targetBg: Float,
            ) = decideMealOnsetBehindEffortVeto(
                delta = delta,
                predictedDelta = predictedDelta,
                acceleration = acceleration,
                predictedBg = predictedBg,
                targetBg = targetBg,
                assessment = onsetAssessment,
                declaredMeal = onsetDeclaredMeal,
                cobG = onsetCobG,
            )

            override fun engine() = engine
        },
    )
    val reason = rT.reason.toString()
    if (reason.isNotEmpty()) consoleLog += reason
    return AimiTickResult(
        command = AimiTherapyCommand.TempBasal(
            rateUPerHour = decision.rate,
            durationMs = decision.duration.toLong() * 60L * 1000L,
        ),
        nextState = state,
        trainingEvents = emptyList(),
        persistenceEvents = emptyList(),
        telemetry = AimiDecisionTrace("SPORT_TBR"),
        safety = AimiSafetyReport(holdReasonCode = null),
    )
}

/** Android shell stand-in: every `gs` is the word `phrase`. */
private object SportBasalPhrase : TextResolver {
    override fun gs(ref: TextRef): String = "phrase"
    override fun gs(ref: TextRef, vararg args: Any?): String = "phrase"
    override fun gsNotLocalised(ref: TextRef): String = "phrase"
    override fun shortTextMode(): Boolean = false
}

private object SportBasalFormatter : DecimalFormatter {
    override fun to0Decimal(value: Double): String = value.toString()
    override fun to0Decimal(value: Double, unit: String): String = value.toString()
    override fun to1Decimal(value: Double): String = value.toString()
    override fun to1Decimal(value: Double, unit: String): String = value.toString()
    override fun to2Decimal(value: Double): String = value.toString()
    override fun to2Decimal(value: Double, unit: String): String = value.toString()
    override fun to3Decimal(value: Double): String = value.toString()
    override fun to3Decimal(value: Double, unit: String): String = value.toString()
    override fun toPumpSupportedBolus(value: Double, bolusStep: Double): String = value.toString()
    override fun toPumpSupportedBolusWithUnits(value: Double, bolusStep: Double): String = value.toString()
    override fun toPumpSupportedBolusWithUnits(value: PumpInsulin, bolusStep: Double): String = value.cU.toString()
    override fun pumpSupportedBolusFormat(bolusStep: Double): NumberFormat = NumberFormat()
}

private object SportBasalNoOpLogger : AAPSLogger {
    override fun debug(message: String) = Unit
    override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, message: String) = Unit
    override fun debug(tag: LTag, accessor: () -> String) = Unit
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun warn(tag: LTag, message: String) = Unit
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun info(tag: LTag, message: String) = Unit
    override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(tag: LTag, message: String) = Unit
    override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
    override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
    override fun error(message: String) = Unit
    override fun error(message: String, throwable: Throwable) = Unit
    override fun error(format: String, vararg arguments: Any?) = Unit
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
}
