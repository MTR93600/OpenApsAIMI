package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshotBuilder
import app.aaps.plugins.aps.openAPSAIMI.orchestration.PredictionAuthorityApplier
import app.aaps.plugins.aps.openAPSAIMI.orchestration.PredictionAuthorityApplyResult
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthorityResolver
import app.aaps.plugins.aps.openAPSAIMI.risk.MealConfirmedEarlyReleaseLatch
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis

/**
 * Android reads of the dose-terminal publish.
 * Each method is the call that already existed at that line.
 * `applyTubeAdvisorFromDoseSnapshot` is the Android shell of `decideApplyTubeAdvisorFromDoseSnapshot`.
 */
internal interface AimiPublishDoseTerminalCalls {
    fun bg(): Double
    fun scenarioProjection(): ScenarioProjectionPair?
    fun mealAbsorption(): MealAbsorptionPhaseEngine.Output?
    fun hypothesis(): UamHypothesisState?
    fun latent(): PhysioLatentState?
    fun causalPosterior(): CausalStatePosterior?
    fun trajectoryAnalysis(): TrajectoryAnalysis?
    fun physioPolicy(): BehavioralRiskPolicy?
    fun uamConfidence(): Double
    fun postHypo(): PostHypoDeliveryAuthority.Decision
    fun mealCertainty(): MealCertainty?
    fun trunk(): GlobalPhysiologicalState?
    fun combinedDelta(): Double
    fun iob(): Double
    fun maxIob(): Double
    fun mcerLatch(): MealConfirmedEarlyReleaseLatch.State
    fun anticipTime(): Boolean
    fun setLatch(value: MealConfirmedEarlyReleaseLatch.State)
    fun setAuthority(value: DecisionPredictionAuthority)
    fun setApplyResult(value: PredictionAuthorityApplyResult)
    fun writeEventual(mgdl: Double, rT: RT)
    fun delta(): Double
    fun sportTime(): Boolean
    fun setSnapshot(value: DoseTerminalSnapshot)
    fun applyTube(profile: OapsProfileAimi, mealData: MealData, targetBgMgdl: Double, stageTag: String)
}

/** Clamp digestion arm: tree / absorption / meal-priority only — not MealCertainty alone. */
private fun digestionOrMealActiveForDose(
    mealPriorityContext: Boolean,
    phase: MealAbsorptionPhase?,
    trunk: GlobalPhysiologicalState?,
): Boolean {
    val mealAbsorptionActive = when (phase) {
        MealAbsorptionPhase.FIRST_WAVE,
        MealAbsorptionPhase.SECOND_WAVE,
        MealAbsorptionPhase.INTER_WAVE,
        MealAbsorptionPhase.PEAK_CORRECTION,
        -> true
        else -> false
    }
    val treeDigestionOrMeal = when (trunk) {
        GlobalPhysiologicalState.DIGESTION_ACTIVE,
        GlobalPhysiologicalState.MEAL_PROBABLE,
        -> true
        else -> false
    }
    return treeDigestionOrMeal || mealAbsorptionActive || mealPriorityContext
}

internal fun decidePublishDoseTerminalAuthorityAndSnapshot(
    rT: RT,
    profile: OapsProfileAimi,
    mealData: MealData,
    pkpdEventualMgdl: Double,
    pkpdPredTerminalMgdl: Double,
    targetBgMgdl: Double,
    stageTag: String,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiPublishDoseTerminalCalls,
) {
    val authorityEnabled = preferences.get(BooleanKey.OApsAIMIPredictionAuthorityEnabled)
    val authorityShadow = preferences.get(BooleanKey.OApsAIMIPredictionAuthorityShadow)
    val decisionPrediction = DecisionPredictionAuthorityResolver.resolve(
        bgMgdl = calls.bg(),
        pkpdEventualMgdl = pkpdEventualMgdl,
        scenarioProjection = calls.scenarioProjection(),
        mealAbsorptionOutput = calls.mealAbsorption(),
        hypothesisState = calls.hypothesis(),
        latentState = calls.latent(),
        causalStatePosterior = calls.causalPosterior(),
        trajectoryAnalysis = calls.trajectoryAnalysis(),
        physioPolicy = calls.physioPolicy(),
        uamConfidence = calls.uamConfidence(),
        postHypoDelivery = calls.postHypo(),
        mealCertainty = calls.mealCertainty(),
        trunkGlobalState = calls.trunk(),
        mealConfirmedEarlyReleaseEnabled = preferences.get(BooleanKey.OApsAIMIMealConfirmedEarlyRelease),
        // The smoothed combined delta, not the raw 5-minute one. The parameter has always been
        // named for the combined signal; passing the raw delta let a single sensor step of +24
        // satisfy the "rising" test and clear the "falling" breaker on the same tick.
        combinedDeltaMgdl5m = calls.combinedDelta(),
        targetBgMgdl = targetBgMgdl,
        iobU = calls.iob(),
        maxIobU = calls.maxIob(),
        mcerTailLatched = calls.mcerLatch().latched,
        declaredMeal = calls.anticipTime() && preferences.get(BooleanKey.OApsAIMIAnticipMealEvidence),
    )
    // Carry the latch to the next tick. Done after the call because the resolver is stateless and
    // reports the trip; it can only keep an opt-in escalation off, never raise a dose.
    calls.setLatch(
        MealConfirmedEarlyReleaseLatch.next(
            previous = calls.mcerLatch(),
            armedThisTick = decisionPrediction.mcerArmed,
            tailTripped = decisionPrediction.mcerTailTripped,
            bgMgdl = calls.bg().toDouble(),
            targetBgMgdl = targetBgMgdl,
            iobU = calls.iob(),
        ),
    )
    calls.setAuthority(decisionPrediction)
    consoleLog.add(
        DecisionPredictionAuthorityResolver.formatLogLine(decisionPrediction) + " [$stageTag]",
    )
    val applyResult = PredictionAuthorityApplier.apply(
        rT = rT,
        authority = decisionPrediction,
        scenarioProjection = calls.scenarioProjection(),
        enabled = authorityEnabled,
        shadowOnly = authorityShadow && !authorityEnabled,
        pkpdEventualBeforeApply = pkpdEventualMgdl,
        pkpdPredTerminalBeforeApply = pkpdPredTerminalMgdl,
    )
    calls.setApplyResult(applyResult)
    PredictionAuthorityApplier.formatShadowLogLine(applyResult)?.let { line -> consoleLog.add(line) }
    if (applyResult.applied) {
        calls.writeEventual(applyResult.eventualMgdl, rT)
        consoleLog.add(
            "PRED_AUTHORITY_C1[$stageTag]: eventual=${applyResult.eventualMgdl.toInt()} " +
                "predT=${applyResult.predTerminalMgdl.toInt()} " +
                "curves=${applyResult.predBGsRemapped} src=${applyResult.source}",
        )
    }
    val mealPriorityContext = calls.mealAbsorption()?.mealDeliveryPriority == true
    val scenarioBest = calls.scenarioProjection()?.scenarioBest
    val doseSnapshot = DoseTerminalSnapshotBuilder.build(
        authority = decisionPrediction,
        applyResult = applyResult,
        authorityEnabled = authorityEnabled,
        fallbackEventualMgdl = pkpdEventualMgdl,
        fallbackMinPredMgdl = pkpdPredTerminalMgdl,
        clampInput = ClampPkpdScenarioReconcile.Input(
            bgMgdl = calls.bg(),
            targetBgMgdl = targetBgMgdl,
            deltaMgdl5m = calls.delta(),
            pkpdEventualMgdl = pkpdEventualMgdl,
            scenarioTerminalMgdl = scenarioBest?.terminalMgdl,
            scenarioPathMinMgdl = scenarioBest?.gatePathMinMgdl,
            scenarioPathMinHitFloor = scenarioBest?.gatePathMinHitFloor == true,
            digestionOrMealActive = digestionOrMealActiveForDose(
                mealPriorityContext,
                calls.mealAbsorption()?.phase,
                calls.trunk(),
            ),
            sportTime = calls.sportTime(),
            postHypoDeliveryActive = calls.postHypo().active,
        ),
    )
    calls.setSnapshot(doseSnapshot)
    calls.writeEventual(doseSnapshot.eventualMgdl, rT)
    consoleLog.add(DoseTerminalSnapshot.formatLogLine(doseSnapshot) + " [$stageTag]")
    if (doseSnapshot.clampReconciled) {
        consoleLog.add(
            "🩹 CLAMP_RECONCILE (in snapshot)[$stageTag] reason=${doseSnapshot.clampReason} " +
                "ev=${doseSnapshot.eventualMgdl.toInt()}",
        )
    }
    calls.applyTube(profile, mealData, targetBgMgdl, stageTag)
}
