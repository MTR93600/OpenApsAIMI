package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety
import app.aaps.plugins.aps.openAPSAIMI.safety.clampSmbToMaxSmbAndMaxIob
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineAmpAxis
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndocrineAmplitudeGovernor
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleBelief
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleInfo

internal data class AimiSafetyGuardApply(
    val smbOut: Float,
    val skippedDuplicate: Boolean,
)

/**
 * Calls and field reads of `applySafetyPrecautions`, each at the line that uses it.
 * The callees stay Android.
 */
internal interface AimiSafetyPrecautionsCalls {
    fun mealWeights(mealData: MealData, hypoThreshold: Double): AimiHypoSmbSafety.MealAggressionWeights
    fun critical(mealData: MealData, hypoThreshold: Double): Pair<Boolean, String>
    fun markCriticalSafetyZeroed()
    fun sportSafety(): Boolean
    fun ensureWCycleInfo(): WCycleInfo?
    fun wCycleBelief(): WCycleBelief?
    fun updateWCycleLearner(needSmbScale: Double?)
    fun logEndocrineSmb(need: Double?, endocrineSmbAmp: Double)
    fun specificAdjustments(smbAmount: Float, ignoreSafetyRestrictions: Boolean): Float
    fun mealTime(): Boolean
    fun bfastTime(): Boolean
    fun lunchTime(): Boolean
    fun dinnerTime(): Boolean
    fun highCarbTime(): Boolean
    fun snackTime(): Boolean
    fun confirmedHighRiseThisTick(): Boolean
    fun mealAdvisorOneShotThisTick(): Boolean
    fun windowSinceLastPkpdDoseMin(): Double
    fun applyPkpdGuard(
        smbIn: Float,
        pkpdRuntime: PkPdRuntime?,
        windowSinceLastDoseMin: Double,
        anyMealModeForGuard: Boolean,
        isConfirmedHighRise: Boolean,
        mealAdvisorOneShot: Boolean,
        reason: StringBuilder?,
    ): AimiSafetyGuardApply
    fun finalizeSmb(smbToGive: Float): Float
    fun maxSMB(): Double
    fun maxIob(): Double
    fun iob(): Float
}

/**
 * `applySafetyPrecautions`.
 *
 * Critical safety and an unscaled sport guard zero the SMB. A meal during sport scales it,
 * then endocrine amplitude, specific adjustments, the PKPD guard, the floor and the max limits
 * run in the same order. This function has no swallowed [Exception]. The critical-condition
 * scan still swallows its own read failure inside the Android port.
 */
internal fun decideSafetyPrecautions(
    mealData: MealData,
    smbToGiveParam: Float,
    hypoThreshold: Double,
    reason: StringBuilder?,
    pkpdRuntime: PkPdRuntime?,
    isConfirmedHighRise: Boolean,
    ignoreSafetyConditions: Boolean,
    preferences: Preferences,
    texts: TextResolver,
    consoleLog: MutableList<String>,
    calls: AimiSafetyPrecautionsCalls,
): Float {
    var smbToGive = smbToGiveParam
    val mealWeights = calls.mealWeights(mealData, hypoThreshold)

    val (isCrit, critMsg) = calls.critical(mealData, hypoThreshold)
    if (isCrit && !ignoreSafetyConditions) {
        calls.markCriticalSafetyZeroed()
        reason?.appendLine("🛑 $critMsg → SMB=0")
        consoleLog.add("SMB forced to 0 by critical safety: $critMsg")
        return 0f
    }

    if (calls.sportSafety()) {
        if (mealWeights.guardScale > 0.0 && smbToGive > 0f) {
            val before = smbToGive
            smbToGive = (smbToGive * mealWeights.guardScale.toFloat()).coerceAtLeast(0f)
            reason?.appendLine(
                texts.gs(ApsStrings.reason_safety_sport_meal_reduction, before, smbToGive)
            )
        } else {
            // Sport is a vital safety, not a minor one, so Red Carpet must not put this back.
            // Without this flag the zero below counted as a minor cut: measured 2026-08-18, the
            // sport guard zeroed four ticks between 19:32 and 19:57 and Red Carpet restored
            // 6.52 U, which took BG from 168.8 down to 50.6 after a three hour hike.
            calls.markCriticalSafetyZeroed()
            reason?.appendLine(texts.gs(ApsStrings.safety_sport_smb_zero))
            consoleLog.add("SMB forced to 0 by sport safety guard")
            return 0f
        }
    }
    val wCycleInfo = calls.ensureWCycleInfo()
    if (wCycleInfo != null) {
        val endocrineSmbAmp = EndocrineAmplitudeGovernor.productionAmp(
            calls.wCycleBelief(),
            EndocrineAmpAxis.SMB,
        )
        if (endocrineSmbAmp != 1.0) {
            val pre = smbToGive
            smbToGive = (smbToGive * endocrineSmbAmp.toFloat()).coerceAtLeast(0f)
            val need = if (pre > 0f) (smbToGive / pre).toDouble() else null
            calls.updateWCycleLearner(need)
            calls.logEndocrineSmb(need, endocrineSmbAmp)
        }
    }
    val beforeAdj = smbToGive
    smbToGive = calls.specificAdjustments(smbToGive, ignoreSafetyRestrictions = ignoreSafetyConditions)
    if (smbToGive != beforeAdj) {
        reason?.appendLine(texts.gs(ApsStrings.adjustments_smb, beforeAdj, smbToGive))
    }
    if (mealWeights.active && mealWeights.boostFactor > 1.0 && smbToGive > 0f) {
        val beforeBoost = smbToGive
        smbToGive = (smbToGive * mealWeights.boostFactor.toFloat()).coerceAtLeast(0f)
        reason?.appendLine(
            texts.gs(
                ApsStrings.reason_meal_aggression_boost,
                beforeBoost,
                smbToGive,
                mealWeights.boostFactor
            )
        )
    }
    val anyMealModeForGuard = calls.mealTime() || calls.bfastTime() || calls.lunchTime() ||
        calls.dinnerTime() || calls.highCarbTime() || calls.snackTime()
    val confirmedHighRiseForGuard = isConfirmedHighRise || calls.confirmedHighRiseThisTick()
    val mealAdvisorForGuard = calls.mealAdvisorOneShotThisTick() ||
        preferences.get(BooleanKey.OApsAIMIMealAdvisorTrigger)
    val pkpdGuardApply = calls.applyPkpdGuard(
        smbIn = smbToGive,
        pkpdRuntime = pkpdRuntime,
        windowSinceLastDoseMin = calls.windowSinceLastPkpdDoseMin(),
        anyMealModeForGuard = anyMealModeForGuard,
        isConfirmedHighRise = confirmedHighRiseForGuard,
        mealAdvisorOneShot = mealAdvisorForGuard,
        reason = reason,
    )
    smbToGive = pkpdGuardApply.smbOut
    if (pkpdGuardApply.skippedDuplicate) {
        consoleLog.add("PKPD_GUARD_SKIP_FINALIZE: guard already applied earlier this tick")
    }

    val beforeFinalize = smbToGive
    smbToGive = calls.finalizeSmb(smbToGive)
    if (smbToGive != beforeFinalize) {
        reason?.appendLine(texts.gs(ApsStrings.finalization_smb, beforeFinalize, smbToGive))
    }

    val beforeLimits = smbToGive
    smbToGive = clampSmbToMaxSmbAndMaxIob(smbToGive, calls.maxSMB(), calls.maxIob(), calls.iob())
    if (smbToGive != beforeLimits) {
        reason?.appendLine(texts.gs(ApsStrings.limits_smb, beforeLimits, smbToGive))
    }
    smbToGive = smbToGive.coerceAtLeast(0f)
    return smbToGive
}
