package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.recursive.BasalFirstChannel
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.recursive.T3cBasalFirstResolution
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoLgsBlockReason
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import kotlin.math.max
import kotlin.math.min

internal data class AimiT3cBasalFirstPlan(
    val rateUph: Double,
    val durationMin: Int,
)

/**
 * Android reads and the block side effect of the native T3C basal-first plan.
 * Each method is the call that already existed at that line.
 */
internal interface AimiT3cBasalFirstCalls {
    fun nativeOwnerConfigured(): Boolean
    fun snapshot(): RecursiveBeliefSnapshot?
    fun usLower(value: String): String
    fun block(state: T3cBasalFirstResolution?, reason: String)
    fun historicalBypassNeutralized(): Boolean
    fun smbAuthorityActive(): Boolean
    fun guardsActive(): Boolean
    fun smbZeroedBySafety(): Boolean
    fun noteGuardBlocked()
    fun exerciseLockout(): Boolean
    fun postHypoActive(): Boolean
    fun iobForGate(): Double
    fun maxIob(): Double
    fun stackingSurveillance(): Boolean
    fun mealContext(): MealSafetyContext
    fun sanitizedPredictedEventual(): Pair<Double, Double>
    fun lgsCurve(): Pair<Double?, Boolean>
    fun bg(): Double
    fun delta(): Double
    fun currentTempDuration(): Int
    fun currentTempRate(): Double
    fun capRate(requestedRateUph: Double, profileBasalUph: Double): Double
    fun markReady()
}

internal fun decideT3cBasalFirstProduction(
    profile: OapsProfileAimi,
    rT: RT,
    consoleLog: MutableList<String>,
    calls: AimiT3cBasalFirstCalls,
): AimiT3cBasalFirstPlan? {
    if (!calls.nativeOwnerConfigured()) return null
    val snapshot = calls.snapshot()
        ?: run {
            calls.block(null, "native_unavailable_no_snapshot")
            return null
        }
    if (snapshot.resolutions.basalFirstChannel != BasalFirstChannel.T3C_BASAL_FIRST) {
        val reason = snapshot.resolutions.t3cBasalFirst?.dominantBlocker?.let(calls::usLower)
            ?: "native_no_basal_first_channel"
        calls.block(snapshot.resolutions.t3cBasalFirst, reason)
        return null
    }
    val t3cState = snapshot.resolutions.t3cBasalFirst
        ?: run {
            calls.block(null, "missing_t3c_state")
            return null
        }

    if (!t3cState.active) {
        calls.block(t3cState, "inactive")
        return null
    }
    if (!t3cState.eligible) {
        val blocker = t3cState.dominantBlocker?.let(calls::usLower) ?: "resolver_ineligible"
        calls.block(t3cState, blocker)
        return null
    }
    if (!calls.historicalBypassNeutralized()) {
        calls.block(t3cState, "historical_bypass_not_neutralized")
        return null
    }
    if (calls.smbAuthorityActive()) {
        calls.block(t3cState, "smb_authority_active")
        return null
    }
    if ((rT.units ?: 0.0) > 0.0 || (rT.insulinReq ?: 0.0) > 0.0) {
        calls.block(t3cState, "smb_already_requested")
        return null
    }
    if (calls.guardsActive() && calls.smbZeroedBySafety()) {
        calls.noteGuardBlocked()
        calls.block(t3cState, "smb_zeroed_by_safety")
        return null
    }
    if (calls.exerciseLockout() || t3cState.exerciseBlock) {
        calls.block(t3cState, "exercise_lockout")
        return null
    }
    if (calls.postHypoActive() || t3cState.postHypoBlock) {
        calls.block(t3cState, "post_hypo_guard")
        return null
    }
    if (t3cState.mealConflict) {
        calls.block(t3cState, "meal_conflict")
        return null
    }
    if (t3cState.hardSafetyBlock) {
        calls.block(t3cState, "hard_safety_block")
        return null
    }
    if (calls.iobForGate() > calls.maxIob()) {
        calls.block(t3cState, "max_iob")
        return null
    }
    if (calls.stackingSurveillance()) {
        calls.block(t3cState, "stacking_cap")
        return null
    }

    val hypoGuard = HypoThresholdMath.computeHypoThreshold(
        minBg = profile.min_bg,
        lgsThreshold = profile.lgsThreshold,
    )
    val mealContext = calls.mealContext()
    val (hypoPredForLgs, hypoEventualForLgs) = calls.sanitizedPredictedEventual()
    val (minPredCurve, ignoreMinPredCurve) = calls.lgsCurve()
    val lgsReason = HypoLgsBlockReason.detect(
        bgNow = calls.bg(),
        predicted = hypoPredForLgs,
        eventual = hypoEventualForLgs,
        minPredictedCurve = minPredCurve,
        hypo = hypoGuard,
        delta = calls.delta(),
        mealContext = mealContext,
        ignoreMinPredictedCurve = ignoreMinPredCurve,
    )
    if (lgsReason != null) {
        calls.block(t3cState, "final_hypo_${calls.usLower(lgsReason.name)}")
        return null
    }

    val previousRate = if (calls.currentTempDuration() > 0) calls.currentTempRate() else profile.current_basal
    val maxStepUp = max(0.30, previousRate * 0.20)
    val rampedRate = if (t3cState.boundedRateUph > previousRate) {
        min(t3cState.boundedRateUph, previousRate + maxStepUp)
    } else {
        t3cState.boundedRateUph
    }
    val finalRate = calls.capRate(
        requestedRateUph = rampedRate,
        profileBasalUph = profile.current_basal,
    ).coerceIn(0.0, t3cState.maxBasalCapUph.coerceAtLeast(rampedRate))
    if (finalRate <= 0.0) {
        calls.block(t3cState, "no_basal_demand")
        return null
    }

    consoleLog.add(
        "🌳 T3C_NATIVE: ready rate=${aimiFmt2(finalRate)}U/h " +
            "demand=${aimiFmt2(t3cState.boundedRateUph)}U/h",
    )
    calls.markReady()
    return AimiT3cBasalFirstPlan(
        rateUph = finalRate,
        durationMin = 30,
    )
}
