package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioContributorId
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.abs

object RecursiveBeliefParadox {

    fun detect(ctx: RecursiveBeliefTickContext, scales: List<BeliefScaleNode>, tensions: List<ScaleTension>): List<BeliefParadox> {
        val byTau = scales.associateBy { it.horizonMinutes }
        val s15 = byTau[15]
        val s180 = byTau[180]
        val out = mutableListOf<BeliefParadox>()

        if (s15 != null && s180 != null && s15.urgency > 0.8 && s180.urgency < 0.0) {
            val physioReboundWait = ctx.behavioralRisk?.capsHtrRelease() == true ||
                ctx.stackingStance?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB ||
                ctx.endogenousCounterRegulatory ||
                ctx.physiologicalPatterns?.suppressHyperRelease == true
            out += paradox(
                BeliefParadoxId.HYPER_VS_CLEARANCE,
                physioReboundWait,
                if (physioReboundWait) "PHYSIO_REBOUND_WAIT" else "P2_SHORT_SCALE_DOMINANCE",
            )
        }
        val bestT = ctx.scenario.scenarioBest.terminalMgdl
        val v3 = ctx.v3SmbU ?: 0.0
        if (bestT > ctx.targetBgMgdl + 80 && v3 < 0.3) {
            out += paradox(BeliefParadoxId.BEST_VS_MPC, false, "RELEASE_SOFT")
        }
        val floorT = ctx.scenario.clinicalFloor.terminalMgdl
        if (floorT < 70 && ctx.bgMgdl > ctx.targetBgMgdl + 60 && !ctx.hypoMinPredIgnored) {
            out += paradox(BeliefParadoxId.FLOOR_VS_REALITY, true, "CREDIBILITY_CASCADE")
        }
        if (ctx.trajectoryAnalysis?.classification == TrajectoryType.TIGHT_SPIRAL && ctx.deltaMgdlPer5 > 2.0) {
            out += paradox(BeliefParadoxId.SPIRAL_VS_RISE, false, "MEAL_FIRST_WAVE_BYPASS")
        }
        if (ctx.iobU > 5.0 && ctx.mealAbsorption?.phase == MealAbsorptionPhase.SECOND_WAVE) {
            out += paradox(BeliefParadoxId.STACK_VS_WAVE, false, "STACK_CRED_ZERO")
        }
        if (ctx.trajBridgePending && ctx.htrResult?.active == true) {
            out += paradox(BeliefParadoxId.TRAJ_TBR_VS_HTR_SMB, false, "CHANNEL_INTERFERENCE")
        }
        val belief15 = s15?.belief ?: 0.0
        val scenPhysio = ctx.scenario.contributors.any {
            it.id == ScenarioContributorId.PHYSIO_REACTIVITY
        }
        if (scenPhysio && ctx.mealAbsorption != null && belief15 < 0.7) {
            out += paradox(BeliefParadoxId.PHYSIO_DAMP_VS_MEAL, belief15 >= 0.7, "BELIEF15_WINS")
        }
        if (ctx.endogenousCounterRegulatory && (ctx.correctionAggressionLevel ?: 0.0) > 0.5) {
            out += paradox(BeliefParadoxId.ENDOG_VS_CORRECTION, false, "BEHAVIORAL_RISK_CAP")
        }
        val sentinelHigh = ctx.shadowAuditorConfidence?.let { it < 0.35 } == true
        if (sentinelHigh && (ctx.htrResult?.active == true || (ctx.v3SmbU ?: 0.0) > 0.5)) {
            out += paradox(BeliefParadoxId.AUDITOR_VS_RELEASE, false, "MIN_SMB_SENTINEL")
        }
        val ngrMult = ctx.ngrSmbMult ?: 1.0
        if (ngrMult < 0.85 && ctx.htrClassification?.tier?.isReleaseEligible == true) {
            out += paradox(BeliefParadoxId.NGR_VS_HYPER, false, "NGR_DAMP_RELEASE")
        }
        if (ctx.physioPhase?.phase == PhysiologicalPhase.STRESS_CORTISOL &&
            (ctx.correctionAggressionLevel ?: 0.0) > 0.6
        ) {
            out += paradox(BeliefParadoxId.THYROID_VS_AGGRESS, false, "THYROID_GUARD_WINS")
        }
        if (ctx.trajectoryAnalysis?.classification == TrajectoryType.STABLE_ORBIT &&
            (ctx.wCycleBasalMult ?: 1.0) != 1.0
        ) {
            out += paradox(BeliefParadoxId.WCYCLE_VS_STABLE, false, "SUPPRESS_RELEASE_STABLE_ORBIT")
        }
        val wCycleUplift = (ctx.wCycleBasalMult ?: 1.0) != 1.0 || (ctx.wCycleSmbMult ?: 1.0) != 1.0
        val wCycleHypoConflict =
            wCycleUplift && (
                ctx.tier1Hypo ||
                    (ctx.minPredictedBgMgdl != null && ctx.minPredictedBgMgdl < 90.0 && !ctx.hypoMinPredIgnored) ||
                    (ctx.wCycleHypoLoad ?: 0.0) >= 0.25
                )
        if (wCycleHypoConflict) {
            out += paradox(BeliefParadoxId.WCYCLE_VS_HYPO, false, "SUPPRESS_RELEASE_WCYCLE_HYPO")
        }
        val patterns = ctx.physiologicalPatterns
        if (patterns?.suppressHyperRelease == true &&
            ((ctx.htrResult?.active == true) || (ctx.v3SmbU ?: 0.0) > 0.3)
        ) {
            out += paradox(BeliefParadoxId.SLEEP_DEBT_VS_HYPER, true, "PATTERN_HYPER_SUPPRESS")
        }
        if (patterns?.suppressMealInterpretation == true &&
            ctx.mealAbsorption?.mealDeliveryPriority == true
        ) {
            out += paradox(BeliefParadoxId.HRV_CRASH_VS_MEAL, true, "PATTERN_MEAL_SUPPRESS")
        }
        if (patterns?.suppressHyperRelease == true &&
            (ctx.correctionAggressionLevel ?: 0.0) > 0.5
        ) {
            out += paradox(BeliefParadoxId.RECOVERY_VS_AGGRESS, true, "PATTERN_RECOVERY_CAP")
        }
        if (ctx.exerciseLockout &&
            ((ctx.htrResult?.active == true) || (ctx.v3SmbU ?: 0.0) > 0.5)
        ) {
            val suppressed = patterns?.suppressHyperRelease == true
            out += paradox(
                BeliefParadoxId.EXERCISE_VS_CORRECTION,
                suppressed,
                if (suppressed) "PATTERN_EXERCISE_CAP" else "EXERCISE_LOCKOUT",
            )
        }
        tensions.filter { it.magnitude > 0.55 }.forEach { t ->
            t.dominantParadoxId?.let { id ->
                if (out.none { it.id == id }) {
                    out += paradox(id, false, "TENSION_${t.parentTauMin}_${t.childTauMin}")
                }
            }
        }
        return out
    }

    fun dominantParadox(parent: BeliefScaleNode, child: BeliefScaleNode): BeliefParadoxId? {
        if (child.horizonMinutes == 60 && parent.horizonMinutes == 180 &&
            child.urgency > 0.8 && parent.urgency < 0.0
        ) {
            return BeliefParadoxId.HYPER_VS_CLEARANCE
        }
        if (abs(child.urgency - parent.urgency) > 0.55) {
            return BeliefParadoxId.BEST_VS_MPC
        }
        return null
    }

    private fun paradox(id: BeliefParadoxId, suppressed: Boolean, resolution: String) =
        BeliefParadox(id, suppressed, resolution)
}
