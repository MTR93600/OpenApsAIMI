package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import kotlin.math.max

/**
 * Phrase book and BG text for the SMB enable gate.
 * Each method is the call that already existed at that line.
 * `convertBG` and `rh.gs` stay Android.
 */
internal interface AimiEnableSmbCalls {
    fun writeMealModeReason(reason: String?)
    fun logError(message: String)
    fun log(message: String)
    fun smbDisabled(): String
    fun convertBg(value: Double): String
    fun smbDisabledHighTarget(targetBg: Double): String
    fun smbEnabledAlways(): String
    fun smbEnabledForCob(cob: Double): String
    fun smbEnabledAfterCarbEntry(): String
    fun smbEnabledForTempTarget(bgText: String): String
    fun smbEnabledMealMode(currentBg: String, combinedDelta: Double, eventualBg: String): String
    fun smbDisabledNoPref(): String
}

internal fun decideEnableSmb(
    profile: OapsProfileAimi,
    microBolusAllowed: Boolean,
    mealData: MealData,
    targetbg: Double,
    mealModeActive: Boolean,
    currentBg: Double,
    delta: Double,
    eventualBg: Double,
    combinedDelta: Double,
    calls: AimiEnableSmbCalls,
): Boolean {
    calls.writeMealModeReason(null)

    // 0) Garde globale
    if (!microBolusAllowed) {
        calls.logError(calls.smbDisabled())
        return false
    }

    // Hard floor. No SMB below 80 mg/dL, even if the prediction is rising.
    if (currentBg < 80) {
        calls.logError("SMB disabled: BG ${calls.convertBg(currentBg)} < 80")
        return false
    }

    val safeFloor = max(100.0, targetbg - 5.0)
    val isMealRise = mealModeActive &&
        (delta >= 0.1) &&
        (currentBg > safeFloor)

    val hypoGuard = HypoThresholdMath.computeHypoThreshold(minBg = profile.min_bg, lgsThreshold = profile.lgsThreshold)
    val mealBypassHighTT = mealModeActive && currentBg > hypoGuard

    if (!profile.allowSMB_with_high_temptarget &&
        profile.temptargetSet && targetbg > 100 &&
        !mealBypassHighTT && !isMealRise
    ) {
        calls.logError(calls.smbDisabledHighTarget(targetbg))
        return false
    }

    if (profile.enableSMB_always) {
        calls.log(calls.smbEnabledAlways())
        return true
    }
    if (profile.enableSMB_with_COB && mealData.mealCOB != 0.0) {
        calls.log(calls.smbEnabledForCob(mealData.mealCOB))
        return true
    }
    if (profile.enableSMB_after_carbs && mealData.carbs != 0.0) {
        calls.log(calls.smbEnabledAfterCarbEntry())
        return true
    }
    if (profile.enableSMB_with_temptarget && profile.temptargetSet && targetbg < 100) {
        calls.log(calls.smbEnabledForTempTarget(calls.convertBg(targetbg)))
        return true
    }

    if (mealModeActive) {
        val safeFloorValue = max(100.0, targetbg - 5)
        val risingFast = combinedDelta >= 2.0 || (combinedDelta > 0 && currentBg > 120)
        val isExplosive = combinedDelta > 4.0 && currentBg > 90.0

        if ((currentBg > safeFloorValue || isExplosive) && combinedDelta > 0.5 && (eventualBg > safeFloorValue || risingFast || isExplosive)) {
            calls.writeMealModeReason(
                calls.smbEnabledMealMode(
                    calls.convertBg(currentBg),
                    combinedDelta,
                    calls.convertBg(eventualBg),
                ) + if (isExplosive) " [🚀 EXPLOSIVE]" else ""
            )
            return true
        }
    }

    calls.logError(calls.smbDisabledNoPref())
    return false
}
