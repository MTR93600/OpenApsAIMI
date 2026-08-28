package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CycleTrackingMode
import kotlin.math.max
import kotlin.math.min

/**
 * Fuses [PhysiologicalPhase] behavioral risk with [PhysioMultipliersMTR] and scenario inputs.
 * Single place for hormonal / cortisol damping shared by scenario engine and doseur.
 */
object PhysioPhaseFusion {

    data class FusedPhysioTick(
        val multipliers: PhysioMultipliersMTR,
        val phaseOutput: PhysiologicalPhaseClassifier.Output,
    )

    /**
     * Estimates scenario best terminal before [app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionEngine] runs (hybrid tail or ramp).
     */
    fun previewBestTerminalMgdl(
        bgMgdl: Double,
        deltaMgdlPer5: Double,
        hybridTerminalMgdl: Double?,
    ): Double {
        if (hybridTerminalMgdl != null && hybridTerminalMgdl.isFinite() && hybridTerminalMgdl > bgMgdl) {
            return hybridTerminalMgdl
        }
        val rampLead = (deltaMgdlPer5 * 6.0).coerceIn(0.0, 45.0)
        return bgMgdl + rampLead
    }

    fun classifyAndFuse(
        classifierInput: PhysiologicalPhaseClassifier.Input,
        baseMultipliers: PhysioMultipliersMTR,
    ): FusedPhysioTick {
        val phaseOutput = PhysiologicalPhaseClassifier.classifyWithHysteresis(classifierInput)
        val fused = applyPhaseToMultipliers(baseMultipliers, phaseOutput.policy)
        return FusedPhysioTick(
            multipliers = fused,
            phaseOutput = phaseOutput,
        )
    }

    fun applyPhaseToMultipliers(
        base: PhysioMultipliersMTR,
        policy: BehavioralRiskPolicy,
    ): PhysioMultipliersMTR {
        if (policy.phase == PhysiologicalPhase.OFF) {
            return base.copy(
                physiologicalPhase = policy.phase,
                phaseConfidence = policy.confidence,
            )
        }
        var smb = base.smbFactor
        var react = base.reactivityFactor
        var basal = base.basalFactor
        val detail = StringBuilder(base.detailedReason)
        when {
            policy.phase.isHormonalRisk -> {
                smb = min(smb, 0.92)
                react = min(react, 0.90)
                basal = max(basal, 1.02).coerceAtMost(PhysioMultipliersMTR.BASAL_MAX)
                detail.append(" | phase=${policy.phase.name} hormonal damp")
            }
            policy.phase == PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY -> {
                smb = min(smb, 0.88)
                react = min(react, 0.88)
                basal = max(basal, 1.08).coerceAtMost(PhysioMultipliersMTR.BASAL_MAX)
                detail.append(" | phase=ENDOGENOUS basal bridge")
            }
            policy.phase == PhysiologicalPhase.STRESS_CORTISOL -> {
                smb = min(smb, 0.94)
                react = min(react, 0.92)
                detail.append(" | phase=STRESS damp")
            }
            policy.phase == PhysiologicalPhase.HYPER_INSTALLED -> {
                detail.append(" | phase=HYPER_INSTALLED pass-through")
            }
            policy.phase.isMealRisk -> {
                detail.append(" | phase=${policy.phase.name} no damp")
            }
        }
        return base.copy(
            smbFactor = smb.coerceIn(PhysioMultipliersMTR.SMB_MIN, PhysioMultipliersMTR.SMB_MAX),
            reactivityFactor = react.coerceIn(PhysioMultipliersMTR.REACTIVITY_MIN, PhysioMultipliersMTR.REACTIVITY_MAX),
            basalFactor = basal.coerceIn(PhysioMultipliersMTR.BASAL_MIN, PhysioMultipliersMTR.BASAL_MAX),
            physiologicalPhase = policy.phase,
            phaseConfidence = policy.confidence,
            detailedReason = detail.toString().trim(),
            source = if (base.source == "Deterministic") "Deterministic+Phase" else "${base.source}+Phase",
        )
    }

    fun buildClassifierInput(
        bgMgdl: Double,
        targetBgMgdl: Double,
        highBgPreferenceMgdl: Double,
        deltaMgdlPer5: Double,
        shortAvgDeltaMgdlPer5: Double,
        combinedDeltaMgdlPer5: Double,
        mealCobG: Double,
        hourOfDay: Int,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        bestTerminalMgdl: Double,
        floorTerminalMgdl: Double,
        dwellAboveHighBgMinutes: Int,
        wCycleEnabled: Boolean,
        wCycleTrackingMode: CycleTrackingMode?,
        wCyclePhase: CyclePhase?,
        htrTier: HyperSeverityTier = HyperSeverityTier.OFF,
        plateauSustain: Boolean = false,
        mealAbsorptionMemoryActive: Boolean = false,
    ): PhysiologicalPhaseClassifier.Input =
        PhysiologicalPhaseClassifier.Input(
            bgMgdl = bgMgdl,
            targetBgMgdl = targetBgMgdl,
            highBgPreferenceMgdl = highBgPreferenceMgdl,
            deltaMgdlPer5 = deltaMgdlPer5,
            shortAvgDeltaMgdlPer5 = shortAvgDeltaMgdlPer5,
            combinedDeltaMgdlPer5 = combinedDeltaMgdlPer5,
            mealCobG = mealCobG,
            hourOfDay = hourOfDay,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            bestTerminalMgdl = bestTerminalMgdl,
            floorTerminalMgdl = floorTerminalMgdl,
            dwellAboveHighBgMinutes = dwellAboveHighBgMinutes,
            wCycleEnabled = wCycleEnabled,
            wCycleTrackingMode = wCycleTrackingMode,
            wCyclePhase = wCyclePhase,
            htrTier = htrTier,
            plateauSustain = plateauSustain,
            mealAbsorptionMemoryActive = mealAbsorptionMemoryActive,
        )
}
