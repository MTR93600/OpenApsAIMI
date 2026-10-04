package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaProductionMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.recursive.BasalFirstChannel
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoLgsBlockReason
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import kotlin.math.max
import kotlin.math.min

internal data class AimiHarmoniaRamp(
    val rateUph: Double,
    val requestedRateUph: Double,
    val sourceAction: HarmoniaAction,
    val branch: String,
)

/**
 * Android reads and writes of [decideHarmoniaProductionRamp], each at the line that uses it.
 */
internal interface AimiHarmoniaRampCalls {
    fun harmoniaDecision(): HarmoniaDecision?
    fun beliefSnapshot(): RecursiveBeliefSnapshot?
    fun block(simulation: HarmoniaDecision, blocker: String)
    fun effectiveAuthority(): ReleaseAuthority?
    fun basalChannelGuardsActive(): Boolean
    fun smbZeroedBySafety(): Boolean
    fun noteBasalChannelBlockedHarmonia()
    fun exerciseLockout(): Boolean
    fun postHypoGuardActive(): Boolean
    fun criticalMealConflict(): Boolean
    fun physioRisk(): PhysiologicalRiskLevel?
    fun iobForGate(): Double
    fun maxIob(): Double
    fun stackingKind(): InsulinStackingStance.Kind?
    fun mealModeActive(): Boolean
    fun manualBolusAgeMin(): Double?
    fun inferredMealIntent(): Boolean
    fun predictedBg(): Float
    fun eventualBg(): Double
    fun sanitizedHypoTerminals(predictedBg: Double, eventualBg: Double): Pair<Double, Double>
    fun lgsMinPredictedCurve(rT: RT): Pair<Double?, Boolean>
    fun bg(): Double
    fun delta(): Float
    fun correctionFragility(): Double
    fun capForCorrectionAggression(requestedRateUph: Double, profileBasalUph: Double, source: String): Double
    fun recordReady(
        requestedRateUph: Double,
        boundedRateUph: Double,
        sourceAction: HarmoniaAction,
        branch: String,
    )
}

/**
 * `planHarmoniaProductionBranch`.
 *
 * A requested basal of 2.00 U/h from a profile basal of 1.00 U/h ramps to 1.30 U/h.
 * Blockers return no plan. This function has no swallowed [Exception].
 */
internal fun decideHarmoniaProductionRamp(
    rT: RT,
    profileMinBg: Double,
    profileMaxBasal: Double,
    profileCurrentBasal: Double,
    profileLgsThreshold: Int?,
    currentTempDuration: Int,
    currentTempRate: Double,
    consoleLog: MutableList<String>,
    calls: AimiHarmoniaRampCalls,
): AimiHarmoniaRamp? {
    val simulation = calls.harmoniaDecision() ?: return null
    val rbtSnapshot = calls.beliefSnapshot()
    val rbtHarmonia = rbtSnapshot?.resolutions?.harmoniaBasalFirst
    if (rbtSnapshot != null) {
        if (rbtSnapshot.resolutions.basalFirstChannel != BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST) {
            val blocker = rbtHarmonia?.dominantBlocker?.lowercase()
                ?: "rbt_no_harmonia_channel"
            calls.block(simulation, blocker)
            return null
        }
        if (rbtHarmonia == null) {
            calls.block(simulation, "rbt_missing_harmonia_state")
            return null
        }
        if (!rbtHarmonia.eligible) {
            val blocker = rbtHarmonia.dominantBlocker?.lowercase()
                ?: "rbt_harmonia_ineligible"
            calls.block(simulation, blocker)
            return null
        }
    }

    val sourceAction = simulation.action
    if (!simulation.eligible) {
        val blocker = simulation.blockers.firstOrNull()?.lowercase() ?: "simulation_ineligible"
        calls.block(simulation, blocker)
        return null
    }
    if (
        sourceAction != HarmoniaAction.BASAL_FIRST &&
        sourceAction != HarmoniaAction.MEAL_SUPPORT &&
        sourceAction != HarmoniaAction.PROTECTIVE_REDUCTION &&
        sourceAction != HarmoniaAction.STABILIZE
    ) {
        calls.block(simulation, "no_production_action")
        return null
    }
    if (!simulation.targetBasalUph.isFinite()) {
        calls.block(simulation, "invalid_basal_demand")
        return null
    }
    val effectiveAuthority = calls.effectiveAuthority()
    if (effectiveAuthority != null && effectiveAuthority != ReleaseAuthority.NONE) {
        // Mirror RBT soft-meal basal exception: DIGESTION MEAL_SUPPORT may own basal under SOFT
        // so production is not starved by smb_authority_active while SMB caps crush delivery.
        val softMealBasalException =
            effectiveAuthority == ReleaseAuthority.SOFT &&
                sourceAction == HarmoniaAction.MEAL_SUPPORT &&
                simulation.branch == "DIGESTION_ACTIVE"
        if (!softMealBasalException) {
            calls.block(simulation, "smb_authority_active")
            return null
        }
    }
    if ((rT.units ?: 0.0) > 0.0 || (rT.insulinReq ?: 0.0) > 0.0) {
        calls.block(simulation, "smb_already_requested")
        return null
    }
    if (calls.basalChannelGuardsActive() && calls.smbZeroedBySafety()) {
        calls.noteBasalChannelBlockedHarmonia()
        calls.block(simulation, "smb_zeroed_by_safety")
        return null
    }
    if (calls.exerciseLockout()) {
        calls.block(simulation, "exercise_lockout")
        return null
    }
    if (calls.postHypoGuardActive()) {
        calls.block(simulation, "post_hypo_guard")
        return null
    }
    if (calls.criticalMealConflict()) {
        calls.block(simulation, "meal_conflict")
        return null
    }
    if (calls.physioRisk() == PhysiologicalRiskLevel.CRITICAL) {
        calls.block(simulation, "critical_physio_risk")
        return null
    }
    if (calls.iobForGate() > calls.maxIob()) {
        calls.block(simulation, "max_iob")
        return null
    }
    if (calls.stackingKind() == InsulinStackingStance.Kind.SURVEILLANCE_IOB) {
        calls.block(simulation, "stacking_cap")
        return null
    }

    val hypoGuard = HypoThresholdMath.computeHypoThreshold(
        minBg = profileMinBg,
        lgsThreshold = profileLgsThreshold,
    )
    val mealContext = MealSafetyContext(
        mealModeActive = calls.mealModeActive(),
        manualBolusAgeMin = calls.manualBolusAgeMin(),
        inferredMealSignal = calls.inferredMealIntent(),
    )
    val (hypoPredForLgs, hypoEventualForLgs) = calls.sanitizedHypoTerminals(
        calls.predictedBg().toDouble(),
        calls.eventualBg(),
    )
    val (minPredCurve, ignoreMinPredCurve) = calls.lgsMinPredictedCurve(rT)
    val lgsReason = HypoLgsBlockReason.detect(
        bgNow = calls.bg(),
        predicted = hypoPredForLgs,
        eventual = hypoEventualForLgs,
        minPredictedCurve = minPredCurve,
        hypo = hypoGuard,
        delta = calls.delta().toDouble(),
        mealContext = mealContext,
        ignoreMinPredictedCurve = ignoreMinPredCurve,
    )
    if (lgsReason != null) {
        calls.block(simulation, "final_hypo_${lgsReason.name.lowercase()}")
        return null
    }

    val profileMax = profileMaxBasal.coerceAtLeast(profileCurrentBasal)
    val envMaxBasal = simulation.environment.maxBasalUph.takeIf { it.isFinite() && it > 0.0 }
        ?: profileMax
    val hardCap = minOf(envMaxBasal, profileMax).coerceAtLeast(0.0)
    val requestedRate = simulation.targetBasalUph.coerceIn(0.0, hardCap)
    val previousRate = if (currentTempDuration > 0) currentTempRate else profileCurrentBasal
    val fragility = calls.correctionFragility()
    val maxStepUp = when {
        fragility >= 0.68 -> max(0.15, previousRate * 0.10)
        fragility >= 0.55 -> max(0.20, previousRate * 0.15)
        else -> max(0.30, previousRate * 0.20)
    }
    val rampedRate = if (requestedRate > previousRate) {
        min(requestedRate, previousRate + maxStepUp)
    } else {
        requestedRate
    }
    val finalRate = calls.capForCorrectionAggression(
        requestedRateUph = rampedRate,
        profileBasalUph = profileCurrentBasal,
        source = "HARMONIA_PRODUCTION_BASAL_FIRST",
    ).coerceIn(0.0, hardCap)
    if (finalRate <= 0.0) {
        calls.block(simulation, "no_basal_demand")
        return null
    }

    calls.recordReady(
        requestedRateUph = simulation.targetBasalUph,
        boundedRateUph = finalRate,
        sourceAction = sourceAction,
        branch = simulation.branch,
    )
    consoleLog.add(
        "🌿 HARMONIA_PROD: ready action=${sourceAction.name} rate=${aimiFmt2(finalRate)}U/h " +
            "requested=${aimiFmt2(simulation.targetBasalUph)}U/h",
    )
    return AimiHarmoniaRamp(
        rateUph = finalRate,
        requestedRateUph = simulation.targetBasalUph,
        sourceAction = sourceAction,
        branch = simulation.branch,
    )
}
