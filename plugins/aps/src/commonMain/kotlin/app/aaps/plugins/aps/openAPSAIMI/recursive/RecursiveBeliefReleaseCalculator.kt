package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseEvaluator
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import kotlin.math.max
import kotlin.math.min

/**
 * Native RBT SMB floor when HTR is deprecated (authority mode) — §7.2 Phase 4.
 */
object RecursiveBeliefReleaseCalculator {

    fun evaluate(ctx: RecursiveBeliefTickContext): HyperTrajectoryReleaseResult? {
        val classification = ctx.htrClassification ?: return null
        return HyperTrajectoryReleaseEvaluator.evaluate(
            HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = ctx.bgMgdl,
                targetBgMgdl = ctx.targetBgMgdl,
                highBgPreferenceMgdl = ctx.highBgPreferenceMgdl,
                deltaMgdlPer5 = ctx.deltaMgdlPer5,
                shortAvgDeltaMgdlPer5 = ctx.shortAvgDeltaMgdlPer5,
                combinedDeltaMgdlPer5 = ctx.combinedDeltaMgdlPer5,
                floorTerminalMgdl = ctx.scenario.clinicalFloor.terminalMgdl,
                bestTerminalMgdl = ctx.scenario.scenarioBest.terminalMgdl,
                tdd24hU = ctx.tdd24hU,
                iobU = ctx.iobU,
                maxIobU = ctx.maxIobU,
                maxSmbEffectiveU = ctx.maxSmbEffectiveU,
                v3SmbU = ctx.v3SmbU ?: 0.0,
                dwellAboveHighBgMinutes = ctx.dwellAboveHighBgMinutes,
                trajectoryType = ctx.trajectoryAnalysis?.classification,
                minPredictedBgMgdl = ctx.minPredictedBgMgdl,
                isNight = ctx.isNight,
                exerciseLockout = ctx.exerciseLockout,
                behavioralRisk = ctx.behavioralRisk,
                mealAbsorptionPhase = ctx.mealAbsorption?.phase ?: MealAbsorptionPhase.NONE,
                gapPrevMgdl = ctx.mealAbsorption?.gapMgdl,
            ),
        )
    }

    fun smbFloor(
        ctx: RecursiveBeliefTickContext,
        releaseAuthority: ReleaseAuthority,
        urgency15: Double,
        urgency60: Double,
    ): Double {
        if (releaseAuthority == ReleaseAuthority.NONE) return 0.0
        val native = evaluate(ctx)
        if (native != null && native.active) {
            return min(native.smbFloorU, ctx.maxSmbEffectiveU.coerceAtLeast(0.0))
        }
        val base = max(0.5, ctx.tdd24hU * 0.025)
        val factor = max(urgency15, urgency60 * 0.85).coerceIn(0.0, 2.5)
        return min(base * factor, ctx.maxSmbEffectiveU.coerceAtLeast(0.0))
    }
}
