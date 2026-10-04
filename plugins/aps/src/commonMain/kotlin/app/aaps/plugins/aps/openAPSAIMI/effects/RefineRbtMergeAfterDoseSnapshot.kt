package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefAuthorityGate
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import kotlin.math.abs

/**
 * Reads and writes the tick fields `refineRbtMergeAfterDoseSnapshot` touches.
 * Each method is the field access at that line.
 */
internal interface AimiRbtRefineState {
    fun resolvedThisTick(): Boolean
    fun previousCommit(): RbtLiveCommitResult?
    fun authorityGate(): RecursiveBeliefAuthorityGate.Decision?
    fun doseSnapshot(): DoseTerminalSnapshot?
    fun physiologicalPhase(): PhysiologicalPhase?
    fun bg(): Double
    fun delta(): Float
    fun shortAvgDelta(): Float
    fun targetBg(): Float
    fun iob(): Float
    fun maxIob(): Double
    fun mealDeliveryPriority(): Boolean
    fun suppressMealInterpretation(): Boolean
    fun mealAbsorptionPhase(): MealAbsorptionPhase?
    fun mealTime(): Boolean
    fun bfastTime(): Boolean
    fun lunchTime(): Boolean
    fun dinnerTime(): Boolean
    fun snackTime(): Boolean
    fun highCarbTime(): Boolean
    fun setStackingEvaluation(value: InsulinStackingStance.Evaluation)
    fun beliefSnapshot(): RecursiveBeliefSnapshot?
    fun setLiveCommit(value: RbtLiveCommitResult)
}

/** `mergeRbtHyperTrajectoryRelease`. The merge itself stays on Android. */
internal fun interface AimiRbtHtrMerge {
    fun merge(
        htr: HyperTrajectoryReleaseResult,
        rbtSnapshot: RecursiveBeliefSnapshot?,
        authorityGate: RecursiveBeliefAuthorityGate.Decision,
        rT: RT,
    ): RbtLiveCommitResult
}

/**
 * `refineRbtMergeAfterDoseSnapshot`.
 *
 * Re-merges the live RBT commit after the late dose snapshot. The min-prediction wiring and the
 * HTR merge stay on Android and are called at the same lines. This function has no swallowed
 * [Exception].
 */
internal fun decideRefineRbtMergeAfterDoseSnapshot(
    rT: RT,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    state: AimiRbtRefineState,
    minPred: AimiMinPredWiring,
    merge: AimiRbtHtrMerge,
) {
    if (!state.resolvedThisTick()) return
    val prev = state.previousCommit() ?: return
    val gate = state.authorityGate() ?: return
    val snap = state.doseSnapshot() ?: return
    val endogenousCounterRegulatory =
        state.physiologicalPhase() == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY
    val stackingEval = InsulinStackingStance.evaluate(
        bg = state.bg(),
        delta = state.delta().toDouble(),
        shortAvgDelta = state.shortAvgDelta().toDouble(),
        targetBg = state.targetBg().toDouble(),
        iob = state.iob().toDouble(),
        maxIob = state.maxIob(),
        eventualBg = snap.eventualMgdl.takeIf { it.isFinite() },
        minPredBg = minPred.bg(snap.minPredMgdl),
        trajectoryEnergy = rT.trajectoryEnergy,
        isExplicitUserAction = false,
        enabled = preferences.get(BooleanKey.OApsAIMIIobSurveillanceGuard),
        mealPriorityContext = state.mealDeliveryPriority() && !state.suppressMealInterpretation(),
        endogenousCounterRegulatory = endogenousCounterRegulatory,
        mealAbsorptionPhase = state.mealAbsorptionPhase() ?: MealAbsorptionPhase.NONE,
        mealModeActive = state.mealTime() || state.bfastTime() || state.lunchTime() ||
            state.dinnerTime() || state.snackTime() || state.highCarbTime(),
    )
    state.setStackingEvaluation(stackingEval)
    val refreshed = merge.merge(
        htr = prev.baselineHtr,
        rbtSnapshot = state.beliefSnapshot(),
        authorityGate = gate,
        rT = rT,
    )
    if (abs(refreshed.effectiveHtr.v3SmbAfterU - prev.effectiveHtr.v3SmbAfterU) > 0.02) {
        consoleLog.add(
            "RBT_REFINE_AFTER_DOSE_SNAPSHOT: " +
                "${aimiFmt2(prev.effectiveHtr.v3SmbAfterU)}→" +
                "${aimiFmt2(refreshed.effectiveHtr.v3SmbAfterU)}U " +
                "ev=${snap.eventualMgdl.toInt()} minPred=${snap.minPredMgdl.toInt()}",
        )
    }
    state.setLiveCommit(refreshed)
}
