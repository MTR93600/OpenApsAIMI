package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import kotlin.math.max
import kotlin.math.min

/** Subtract 70% of current IOB. The other 30% stays, because that IOB may belong to an earlier meal. */
internal const val MEAL_ADVISOR_IOB_DISCOUNT_FACTOR = 0.7

/** Estimates older than this are cleared. A stored carb count must not survive for months. */
internal const val MEAL_ADVISOR_STALE_ESTIMATE_MAX_MIN = 24.0 * 60.0

/** At least 25% of the carb insulin is delivered, even when IOB would otherwise zero the SMB. */
internal const val MEAL_ADVISOR_MIN_CARB_COVERAGE = 0.25

internal fun decideTryMealAdvisor(
    bg: Double,
    delta: Float,
    iobData: IobTotal,
    profile: OapsProfileAimi,
    lastBolusTime: Long,
    modesCondition: Boolean,
    isExplicitTrigger: Boolean,
    hasRecentBolus45m: Boolean,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    logger: AAPSLogger,
): DecisionResult {
    var estimatedCarbs = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbs)
    val estimatedCarbsTime = preferences.get(DoubleKey.OApsAIMILastEstimatedCarbTime).toLong()
    val timeSinceEstimateMin = if (estimatedCarbsTime > 0L) {
        (aimiWallClockMs() - estimatedCarbsTime) / 60000.0
    } else {
        Double.POSITIVE_INFINITY
    }
    if (estimatedCarbs > 0.0 && timeSinceEstimateMin > MEAL_ADVISOR_STALE_ESTIMATE_MAX_MIN) {
        preferences.put(DoubleKey.OApsAIMILastEstimatedCarbs, 0.0)
        preferences.put(DoubleKey.OApsAIMILastEstimatedCarbTime, 0.0)
        logger.debug(
            LTag.APS,
            "MEAL_ADVISOR_TRACE cleared stale estimate carbs=$estimatedCarbs ageMin=${aimiFmt0(timeSinceEstimateMin)}",
        )
        estimatedCarbs = 0.0
    }

    val maxPassiveWindow = if (isExplicitTrigger) 120.0 else 20.0
    logger.debug(
        LTag.APS,
        "MEAL_ADVISOR_TRACE gate carbs=${aimiFmt1(estimatedCarbs)} timeSinceMin=${aimiFmt1(timeSinceEstimateMin)} maxWindow=$maxPassiveWindow bg=${aimiFmt1(bg)} explicit=$isExplicitTrigger modesCondition=$modesCondition"
    )

    if (estimatedCarbs > 10.0 && timeSinceEstimateMin in 0.0..maxPassiveWindow && bg >= 60) {
        if (!isExplicitTrigger && hasRecentBolus45m) {
            logger.debug(
                LTag.APS,
                "MEAL_ADVISOR_TRACE blocked refractory=true explicit=$isExplicitTrigger lastBolusTime=$lastBolusTime"
            )
            return DecisionResult.Fallthrough("Advisor Refractory (Recent Bolus <45m)")
        }

        if (modesCondition || isExplicitTrigger) {
            val maxBasalPref = preferences.get(DoubleKey.meal_modes_MaxBasal)
            val safeMax = if (maxBasalPref > 0.1) maxBasalPref else profile.max_basal
            val insulinForCarbs = estimatedCarbs / profile.carb_ratio
            val effectiveIOB = iobData.iob * MEAL_ADVISOR_IOB_DISCOUNT_FACTOR
            val minimumRequired = insulinForCarbs * MEAL_ADVISOR_MIN_CARB_COVERAGE
            val calculatedNeed = insulinForCarbs - effectiveIOB
            val netNeeded = max(calculatedNeed, minimumRequired).coerceAtLeast(0.0)
            val tbrCoverage = safeMax * 0.5

            consoleLog.add("ADVISOR_CALC carbs=${estimatedCarbs.toInt()}g IC=${profile.carb_ratio} → ${aimiFmt2(insulinForCarbs)}U")
            consoleLog.add("ADVISOR_CALC IOB_raw=${aimiFmt2(iobData.iob)}U × discount=$MEAL_ADVISOR_IOB_DISCOUNT_FACTOR → IOB_effective=${aimiFmt2(effectiveIOB)}U")
            consoleLog.add("ADVISOR_CALC minimumGuaranteed=${aimiFmt2(minimumRequired)}U (${(MEAL_ADVISOR_MIN_CARB_COVERAGE * 100).toInt()}% of carb need)")
            consoleLog.add("ADVISOR_CALC calculated=${aimiFmt2(calculatedNeed)}U → netSMB=${aimiFmt2(netNeeded)}U (max of calculated and minimum)")
            consoleLog.add("ADVISOR_CALC TBR=${aimiFmt1(safeMax)}U/h (will deliver ${aimiFmt2(tbrCoverage)}U over 30min as complement)")
            consoleLog.add("ADVISOR_CALC TOTAL delivery: SMB ${aimiFmt2(netNeeded)}U + TBR ${aimiFmt2(tbrCoverage)}U = ${aimiFmt2(netNeeded + tbrCoverage)}U delta=$delta modesOK=true")

            if (isExplicitTrigger) {
                preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, false)
                consoleLog.add("🚀 MEAL ADVISOR: Trigger Consumed.")
            }

            return DecisionResult.Applied(
                source = "MealAdvisor",
                bolusU = netNeeded,
                tbrUph = safeMax,
                tbrMin = 30,
                reason = "📸 Meal Advisor: ${estimatedCarbs.toInt()}g -> ${aimiFmt2(netNeeded)}U + TBR ${aimiFmt1(safeMax)}U/h"
            )
        } else {
            consoleLog.add("ADVISOR_SKIP reason=modesCondition_false (legacy mode active)")
            logger.debug(
                LTag.APS,
                "MEAL_ADVISOR_TRACE blocked modesCondition=false"
            )
        }
    }
    logger.debug(
        LTag.APS,
        "MEAL_ADVISOR_TRACE fallthrough no_active_request carbs=${aimiFmt1(estimatedCarbs)} timeSinceMin=${aimiFmt1(timeSinceEstimateMin)} bg=${aimiFmt1(bg)}"
    )
    return DecisionResult.Fallthrough("No active Meal Advisor request")
}

internal fun decideMealAdvisorOrReturn(
    bg: Double,
    delta: Float,
    iobData: IobTotal,
    profile: OapsProfileAimi,
    lastBolusTimeMs: Long?,
    modesCondition: Boolean,
    isExplicitAdvisorRun: Boolean,
    hasRecentBolus45m: Boolean,
    autodriveDisplay: String,
    rT: RT,
    currentTemp: CurrentTemp,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    logger: AAPSLogger,
    effects: AimiEffectSink,
    hasHypoRecovery: Boolean,
    adaptiveMult: Double,
    statusLine: (String) -> String,
    onSmbDelivered: (Double) -> Unit,
    logFinal: (RT) -> Unit,
    markFinal: (RT, CurrentTemp) -> Unit,
): RT? {
    val advisorRes = decideTryMealAdvisor(
        bg = bg,
        delta = delta,
        iobData = iobData,
        profile = profile,
        lastBolusTime = lastBolusTimeMs ?: 0L,
        modesCondition = modesCondition,
        isExplicitTrigger = isExplicitAdvisorRun,
        hasRecentBolus45m = hasRecentBolus45m,
        preferences = preferences,
        consoleLog = consoleLog,
        logger = logger,
    )
    if (advisorRes !is DecisionResult.Applied) return null

    consoleLog.add("MEAL_ADVISOR_APPLIED source=${advisorRes.source} bolus=${advisorRes.bolusU}")
    logger.debug(
        LTag.APS,
        "MEAL_ADVISOR_TRACE applied source=${advisorRes.source} explicit=$isExplicitAdvisorRun bolusU=${advisorRes.bolusU} tbrUph=${advisorRes.tbrUph}"
    )

    if (advisorRes.tbrUph != null) {
        effects.setTempBasal(
            advisorRes.tbrUph,
            advisorRes.tbrMin ?: 30,
            profile,
            rT,
            currentTemp,
            overrideSafetyLimits = true,
            forceExact = false,
            adaptiveMultiplier = adaptiveMult,
        )
    }

    val hypoSuppressAdvisorSmb = hasHypoRecovery && !isExplicitAdvisorRun
    if (hypoSuppressAdvisorSmb && (advisorRes.bolusU ?: 0.0) > 0.0) {
        consoleLog.add("🍬 CTX_HYPO_RECOVERY: meal advisor auto-SMB suppressed (intent=${aimiFmt2(advisorRes.bolusU ?: 0.0)}U)")
    }
    val bolusIntent = if (hypoSuppressAdvisorSmb) 0.0 else (advisorRes.bolusU ?: 0.0).toDouble()

    if (bolusIntent > 0) {
        val safeIntent = min(bolusIntent, 30.0)
        effects.applySmbUnits(rT, safeIntent, "MealAdvisor")
        rT.reason.append(advisorRes.reason)
        val triggerType = if (isExplicitAdvisorRun) "Explicit" else "Auto"
        consoleLog.add("🍱 MEAL_ADVISOR_DIRECT_SEND ($triggerType) Pushed=${aimiFmt2(safeIntent)}U (Limits Bypassed)")
        if (safeIntent > 0) onSmbDelivered(safeIntent)
    } else {
        rT.reason.append(advisorRes.reason)
    }

    rT.reason.appendLine(statusLine(autodriveDisplay))
    logFinal(rT)
    logger.debug(
        LTag.APS,
        "MEAL_ADVISOR_TRACE final_return rT.units=${rT.units} insulinReq=${rT.insulinReq} reasonTail=${rT.reason.takeLast(120)}"
    )
    markFinal(rT, currentTemp)
    return rT
}
