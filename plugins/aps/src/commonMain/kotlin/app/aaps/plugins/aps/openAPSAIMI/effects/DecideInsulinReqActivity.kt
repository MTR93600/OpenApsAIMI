package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import kotlin.math.roundToInt

/** Field reads of `runInsulinReqActivityRelaxAndMicrobolusStage`, each at the line that uses it. */
internal interface AimiInsulinReqState {
    fun activityProtectionMode(): Boolean
    fun activityStateIntense(): Boolean
    fun maxSMB(): Double
    fun hyperReleaseFloorU(): Double
}

/** `calculateSMBInterval` at the microbolus line. Hour and interval preferences stay Android. */
internal fun interface AimiInsulinReqSmbInterval {
    fun calculateSMBInterval(): Int
}

/** `finalizeAndCapSMB` at the line the reference calls it. The cap body stays Android. */
internal fun interface AimiInsulinReqFinalize {
    fun finalizeAndCapSMB(
        rT: RT,
        proposedUnits: Double,
        reasonHeader: String,
        mealData: MealData,
        hypoThreshold: Double,
        isExplicitUserAction: Boolean,
        decisionSource: String,
        isMealActive: Boolean,
        hyperReleaseFloorU: Double,
    )
}

/**
 * `runInsulinReqActivityRelaxAndMicrobolusStage`.
 *
 * Activity or recovery cuts the request to half of maxSMB, then meal-high-IOB damping and
 * `bolusFactor` scale it. A microbolus, when allowed, still goes through `finalizeAndCapSMB`.
 * This function has no swallowed [Exception].
 */
internal fun decideInsulinReqActivityRelaxAndMicrobolus(
    ctx: AimiTickContext,
    rT: RT,
    iobTotal: IobTotal,
    smbToGive: Float,
    allowMealHighIob: Boolean,
    mealHighIobDamping: Double,
    maxIobLimit: Double,
    safetyDecision: SafetyDecision,
    enableSMB: Boolean,
    isMealActive: Boolean,
    bg: Double,
    delta: Float,
    hypoThresholdMgdl: Double,
    systemTime: Long,
    basalBoostApplied: Boolean,
    basalBoostSource: String?,
    texts: TextResolver,
    consoleLog: MutableList<String>,
    state: AimiInsulinReqState,
    smbIntervalPort: AimiInsulinReqSmbInterval,
    finalize: AimiInsulinReqFinalize,
) {
    var insulinReq = smbToGive.toDouble()

    if (state.activityProtectionMode() || state.activityStateIntense()) {
        val safetyMax = state.maxSMB() * 0.5
        if (insulinReq > safetyMax) {
            insulinReq = safetyMax
            rT.reason.append(texts.gs(ApsStrings.reason_activity_cap, safetyMax))
            consoleLog.add("SMB capped by Activity/Recovery (Limit: ${aimiFmt2(safetyMax)})")
        }
    }

    if (allowMealHighIob) {
        insulinReq *= mealHighIobDamping
        rT.reason.append(
            texts.gs(
                ApsStrings.reason_meal_high_iob_relaxed,
                AimiTickPolicyMath.round(iobTotal.iob, 2),
                AimiTickPolicyMath.round(maxIobLimit, 2),
                (mealHighIobDamping * 100).roundToInt()
            )
        )
    }

    insulinReq = insulinReq * safetyDecision.bolusFactor
    insulinReq = AimiTickPolicyMath.round(insulinReq, 3)
    rT.insulinReq = insulinReq
    val lastBolusAge = AimiTickPolicyMath.round((systemTime - iobTotal.lastBolusTime) / 60000.0, 1)

    if (basalBoostApplied) {
        consoleLog.add("SMB_FLOW_CONTINUES afterBasalBoost=true source=${basalBoostSource ?: "?"}")
    }

    if (ctx.microBolusAllowed && enableSMB) {
        val microBolus = insulinReq
        rT.reason.append(texts.gs(ApsStrings.reason_insulin_required, insulinReq))
        if (microBolus >= state.maxSMB()) {
            rT.reason.append(texts.gs(ApsStrings.reason_max_smb, state.maxSMB()))
        }
        rT.reason.append(". ")

        val smbInterval = smbIntervalPort.calculateSMBInterval()
        val intervalStr = aimiFmt1(smbInterval.toDouble())
        val lastBolusStr = aimiFmt1(lastBolusAge)
        val deltaStr = aimiFmt1(delta.toDouble())
        rT.reason.append(" [SMB interval=")
        rT.reason.append(intervalStr)
        rT.reason.append(" min, lastBolusAge=")
        rT.reason.append(lastBolusStr)
        rT.reason.append(" min, Δ=")
        rT.reason.append(deltaStr)
        rT.reason.append(", BG=")
        rT.reason.append(bg.toInt().toString())
        rT.reason.append("] ")

        val nextBolusMins = AimiTickPolicyMath.round(smbInterval - lastBolusAge, 0)
        val nextBolusSeconds = AimiTickPolicyMath.round((smbInterval - lastBolusAge) * 60, 0) % 60
        if (lastBolusAge > smbInterval) {
            if (microBolus > 0) {
                val htrFloorU = state.hyperReleaseFloorU()
                finalize.finalizeAndCapSMB(
                    rT = rT,
                    proposedUnits = microBolus,
                    reasonHeader = texts.gs(ApsStrings.reason_microbolus, microBolus),
                    mealData = ctx.mealData,
                    hypoThreshold = hypoThresholdMgdl,
                    isExplicitUserAction = false,
                    decisionSource = "GlobalAIMI",
                    isMealActive = isMealActive,
                    hyperReleaseFloorU = htrFloorU,
                )
            }
        } else {
            rT.reason.append(
                texts.gs(
                    ApsStrings.reason_wait_microbolus,
                    nextBolusMins,
                    nextBolusSeconds
                )
            )
        }
    }
}
