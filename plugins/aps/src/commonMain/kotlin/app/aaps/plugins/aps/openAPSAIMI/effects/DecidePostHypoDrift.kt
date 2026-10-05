package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext

/**
 * Android reads of the post-hypo compression and drift terminator.
 * Compression, the drift predicate, SMB finalize, and the final log stay Android.
 */
internal interface AimiPostHypoDriftCalls {
    fun compression(delta: Float, reason: StringBuilder): Boolean
    fun drift(
        bg: Float,
        targetBg: Float,
        delta: Float,
        avgDelta: Float,
        combinedDelta: Float,
        minDeviation: Double,
        lastBolusVolume: Double,
        reason: StringBuilder,
    ): Boolean
    fun maxSmb(): Double
    fun writeMaxSmb(value: Double)
    fun finalize(
        rT: RT,
        proposedUnits: Double,
        reasonHeader: String,
        mealData: MealData,
        hypoThreshold: Double,
        isExplicitUserAction: Boolean,
        decisionSource: String,
        isMealActive: Boolean,
        hyperReleaseFloorU: Double,
        bypassSmbRefractory: Boolean,
    )
    fun logFinal(tag: String, rT: RT, bg: Double, delta: Float)
    fun markFinal(rT: RT, currentTemp: CurrentTemp?)
}

internal fun decidePostHypoCompressionAndDriftTerminatorOrReturn(
    ctx: AimiTickContext,
    rT: RT,
    bg: Double,
    delta: Float,
    threshold: Double,
    combinedDelta: Float,
    shortAvgDeltaRawForDrift: Float,
    targetBgMgdl: Float,
    postHypoState: PostHypoState,
    autosensRatio: Double,
    nightbis: Boolean,
    autodriveEnabledPref: Boolean,
    modesCondition: Boolean,
    hasRecentBolus45m: Boolean,
    totalBolusLastHour: Double,
    dynamicPbolusSmall: Double,
    exerciseInsulinLockoutActive: Boolean,
    reason: StringBuilder,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiPostHypoDriftCalls,
): RT? {
    val isPostHypo = postHypoState !is PostHypoState.None

    val isCompression = calls.compression(delta.toFloat(), reason)

    if (isCompression) {
        calls.logFinal("COMPRESSION", rT, bg, delta)
        calls.markFinal(rT, ctx.currentTemp)
        return rT
    }

    val terminatorThresholdAdd = when {
        autosensRatio < 0.8 -> 10.0
        autosensRatio > 1.2 -> 30.0
        else -> 15.0
    }
    val terminatorTarget = targetBgMgdl + terminatorThresholdAdd

    if (!nightbis && autodriveEnabledPref && bg >= 80 && !isPostHypo && !hasRecentBolus45m &&
        calls.drift(
            bg.toFloat(),
            terminatorTarget.toFloat(),
            delta.toFloat(),
            shortAvgDeltaRawForDrift,
            combinedDelta.toFloat(),
            ctx.mealData.slopeFromMinDeviation,
            totalBolusLastHour,
            reason,
        ) && modesCondition
    ) {
        val terminatortap = dynamicPbolusSmall

        if (calls.maxSmb() < 0.1 && !exerciseInsulinLockoutActive) {
            calls.writeMaxSmb(preferences.get(DoubleKey.OApsAIMIMaxSMB))
            if (calls.maxSmb() < 0.1) calls.writeMaxSmb(0.5)
            reason.append(" [Drift Override]")
            consoleLog.add("⚡ DriftTerminator: Overrode Basal-First block (MaxSMB 0.0 -> ${aimiFmt2(calls.maxSmb())})")
        }

        reason.append("→ Drift Terminator (Trigger +${terminatorThresholdAdd}): Micro-Tap ${terminatortap}U\n")
        consoleLog.add("AD_EARLY_TBR_TRIGGER rate=0.0 duration=0 reason=DriftTerminator_Tap")
        consoleLog.add("AD_SMALL_PREBOLUS_TRIGGER amount=$terminatortap reason=DriftTerminator")
        calls.finalize(
            rT,
            terminatortap,
            reason.toString(),
            ctx.mealData,
            threshold,
            isExplicitUserAction = false,
            decisionSource = "DriftTerminator",
            isMealActive = false,
            hyperReleaseFloorU = 0.0,
            bypassSmbRefractory = false,
        )
        calls.logFinal("DRIFT_TERMINATOR", rT, bg, delta)
        calls.markFinal(rT, ctx.currentTemp)
        return rT
    }

    return null
}
