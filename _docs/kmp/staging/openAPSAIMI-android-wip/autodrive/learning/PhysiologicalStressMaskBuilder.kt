package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioDecisionTraceMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import java.util.Locale

internal data class PhysiologicalStressMask(
    val autonomicStress: Double,
    val inflammationRecovery: Double,
    val hormonalCircadian: Double,
) {
    fun toArray(): DoubleArray = doubleArrayOf(
        autonomicStress,
        inflammationRecovery,
        hormonalCircadian,
    )

    fun isActive(threshold: Double = 0.05): Boolean =
        autonomicStress >= threshold ||
            inflammationRecovery >= threshold ||
            hormonalCircadian >= threshold

    fun toDebugString(): String =
        "auto=${autonomicStress.formatMask()} inflam=${inflammationRecovery.formatMask()} hormonal=${hormonalCircadian.formatMask()}"

    private fun Double.formatMask(): String = String.format(Locale.US, "%.2f", this)
}

/**
 * Builds the 3-axis Autodrive attention mask expected by [MechanismAttentionGate]:
 * `mask[0] = autonomic stress`, `mask[1] = inflammation / recovery burden`,
 * `mask[2] = hormonal / circadian resistance`.
 *
 * The builder intentionally reuses existing AIMI state objects instead of inventing a
 * second physiology model for V3.
 */
internal object PhysiologicalStressMaskBuilder {

    fun build(
        snapshot: HealthContextSnapshot,
        physioContext: PhysioContextMTR?,
        physioTrace: PhysioDecisionTraceMTR?,
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        chronicInflammation: InflammationAdjuster.InflammationResult?,
    ): PhysiologicalStressMask {
        return PhysiologicalStressMask(
            autonomicStress = buildAutonomicStress(
                snapshot = snapshot,
                physioContext = physioContext,
                phaseOutput = phaseOutput,
                patternSnapshot = patternSnapshot,
            ),
            inflammationRecovery = buildInflammationRecovery(
                snapshot = snapshot,
                physioContext = physioContext,
                physioTrace = physioTrace,
                patternSnapshot = patternSnapshot,
                correctionAggressionDecision = correctionAggressionDecision,
                chronicInflammation = chronicInflammation,
            ),
            hormonalCircadian = buildHormonalCircadian(
                phaseOutput = phaseOutput,
                patternSnapshot = patternSnapshot,
            ),
        )
    }

    private fun buildAutonomicStress(
        snapshot: HealthContextSnapshot,
        physioContext: PhysioContextMTR?,
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val exerciseActive = patternSnapshot.hasAny(
            PhysiologicalPatternId.EXERCISE_ACUTE,
            PhysiologicalPatternId.EXERCISE_LOCKOUT,
            PhysiologicalPatternId.POST_EXERCISE_SENSITIVITY,
        )
        val rawSns = ((snapshot.toSNSDominance() - 0.30) / 0.70).coerceIn(0.0, 1.0)
        val phaseStress = if (phaseOutput?.phase == PhysiologicalPhase.STRESS_CORTISOL) {
            phaseOutput.confidence
        } else {
            0.0
        }
        val explicitStressPatterns = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.STRESS_CORTISOL_ACUTE,
            PhysiologicalPatternId.PSYCHOSOCIAL_STRESS,
            PhysiologicalPatternId.CONTEXT_STRESS_INTENT,
        )
        val recoveryStressPatterns = patternSnapshot.maxConfidence(PhysiologicalPatternId.HRV_DEPRESSED)
        val contextStress = when {
            physioContext == null -> 0.0
            physioContext.state == PhysioStateMTR.INFECTION_RISK ->
                combineSignals(
                    0.55,
                    physioContext.confidence.coerceIn(0.0, 1.0),
                )

            physioContext.state == PhysioStateMTR.STRESS_DETECTED ->
                combineSignals(
                    0.50,
                    physioContext.confidence.coerceIn(0.0, 1.0),
                )

            physioContext.hrvDepressed && physioContext.rhrElevated -> 0.68
            physioContext.hrvDepressed || physioContext.rhrElevated -> 0.42
            else -> 0.0
        }

        val combined = combineSignals(
            rawSns,
            phaseStress,
            explicitStressPatterns,
            recoveryStressPatterns * 0.55,
            contextStress,
        )
        val explicitStressSignal =
            phaseStress >= 0.35 ||
                explicitStressPatterns >= 0.35 ||
                physioContext?.state == PhysioStateMTR.STRESS_DETECTED ||
                physioContext?.state == PhysioStateMTR.INFECTION_RISK

        return if (exerciseActive && !explicitStressSignal) {
            combined.coerceAtMost(0.18)
        } else {
            combined
        }.coerceIn(0.0, 1.0)
    }

    private fun buildInflammationRecovery(
        snapshot: HealthContextSnapshot,
        physioContext: PhysioContextMTR?,
        physioTrace: PhysioDecisionTraceMTR?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        chronicInflammation: InflammationAdjuster.InflammationResult?,
    ): Double {
        val latentInflammation =
            (physioTrace?.inflammationLatentIndex ?: 0.0)
                .coerceIn(0.0, 1.0) *
                ((physioTrace?.inflammationConfidence ?: 0.0).coerceIn(0.0, 1.0).coerceAtLeast(0.35))
        val contextBurden = when {
            physioContext == null -> 0.0
            physioContext.state == PhysioStateMTR.INFECTION_RISK ->
                combineSignals(0.72, physioContext.confidence.coerceIn(0.0, 1.0))

            physioContext.state == PhysioStateMTR.RECOVERY_NEEDED ->
                combineSignals(0.48, physioContext.confidence.coerceIn(0.0, 1.0))

            physioContext.poorSleepDetected -> 0.42
            else -> 0.0
        }
        val sleepDebtBurden = when {
            snapshot.sleepDebtMinutes >= 120 -> 0.72
            snapshot.sleepDebtMinutes >= 60 -> 0.54
            snapshot.sleepDebtMinutes >= 30 -> 0.28
            else -> 0.0
        }
        val patternBurden = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.INFECTION_RISK,
            PhysiologicalPatternId.RECOVERY_NEEDED,
            PhysiologicalPatternId.SLEEP_DEBT,
            PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
            PhysiologicalPatternId.CONTEXT_ILLNESS,
            PhysiologicalPatternId.POST_HYPO_REBOUND,
        )
        val reboundBurden = when (correctionAggressionDecision?.tier) {
            CorrectionAggressionGate.Tier.REBOUND_GUARD -> 0.58
            else -> 0.0
        }
        val chronicBurden = if (
            chronicInflammation != null &&
            (
                chronicInflammation.reason.isNotBlank() ||
                    chronicInflammation.basalMultiplier != 1.0 ||
                    chronicInflammation.smbMultiplier != 1.0 ||
                    chronicInflammation.isfMultiplier != 1.0
                )
        ) {
            0.35
        } else {
            0.0
        }

        return combineSignals(
            latentInflammation,
            contextBurden,
            sleepDebtBurden,
            patternBurden,
            reboundBurden,
            chronicBurden,
        )
    }

    private fun buildHormonalCircadian(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val phaseSignal = when {
            phaseOutput == null -> 0.0
            phaseOutput.phase.isHormonalRisk -> phaseOutput.confidence
            phaseOutput.phase.isEndogenousRisk -> phaseOutput.confidence * 0.92
            else -> 0.0
        }
        val extendedDawnGuard = if (phaseOutput?.policy?.extendedDawnGuard == true) 0.55 else 0.0
        val patternSignal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.DAWN_CORTISOL,
            PhysiologicalPatternId.MALE_CIRCADIAN_HORMONAL,
            PhysiologicalPatternId.FEMALE_CYCLE_HORMONAL,
            PhysiologicalPatternId.ENDOGENOUS_COUNTER_REGULATORY,
            PhysiologicalPatternId.NGR_NIGHT_GROWTH,
        )
        return combineSignals(
            phaseSignal,
            extendedDawnGuard,
            patternSignal,
        )
    }

    private fun combineSignals(vararg values: Double): Double {
        if (values.isEmpty()) return 0.0
        var remainingNeutral = 1.0
        for (value in values) {
            remainingNeutral *= 1.0 - value.coerceIn(0.0, 1.0)
        }
        return (1.0 - remainingNeutral).coerceIn(0.0, 1.0)
    }

    private fun PhysiologicalPatternSnapshot?.hasAny(vararg ids: PhysiologicalPatternId): Boolean =
        this?.active?.any { reading -> reading.id in ids && reading.confidence >= 0.35 } == true

    private fun PhysiologicalPatternSnapshot?.maxConfidence(vararg ids: PhysiologicalPatternId): Double =
        this?.active
            ?.filter { reading -> reading.id in ids }
            ?.maxOfOrNull { reading -> reading.confidence.coerceIn(0.0, 1.0) }
            ?: 0.0
}
