package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.PhysiologicalStressMaskBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentStateBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisStateBuilder

/**
 * Recomputes patient-mode presentation from cached loop outputs plus fresh wearable/context signals.
 */
internal object PatientStateRuntimeRefresher {

    fun rebuildAndPublish(
        cache: PatientStateLoopCache,
        healthSnapshot: HealthContextSnapshot,
        contextSnapshot: ContextSnapshot?,
        nowMs: Long,
    ): PatientRuntimeSnapshot? {
        val hypothesisState = UamHypothesisStateBuilder.build(
            phaseOutput = cache.phaseOutput,
            mealAbsorptionOutput = cache.mealAbsorptionOutput,
            patternSnapshot = cache.patternSnapshot,
            correctionAggressionDecision = cache.correctionAggressionDecision,
            uamConfidence = cache.uamConfidence,
        )
        val stressMask = PhysiologicalStressMaskBuilder.build(
            snapshot = healthSnapshot,
            physioContext = cache.physioContext,
            physioTrace = cache.physioTrace,
            phaseOutput = cache.phaseOutput,
            patternSnapshot = cache.patternSnapshot,
            correctionAggressionDecision = cache.correctionAggressionDecision,
            chronicInflammation = cache.chronicInflammation,
        )
        val latentState = PhysioLatentStateBuilder.build(
            snapshot = healthSnapshot,
            sourceSensor = cache.sourceSensor,
            phaseOutput = cache.phaseOutput,
            mealAbsorptionOutput = cache.mealAbsorptionOutput,
            hypothesisState = hypothesisState,
            patternSnapshot = cache.patternSnapshot,
            physioContext = cache.physioContext,
            physioTrace = cache.physioTrace,
            correctionAggressionDecision = cache.correctionAggressionDecision,
            chronicInflammation = cache.chronicInflammation,
            autonomicStress = stressMask.autonomicStress,
            inflammationRecovery = stressMask.inflammationRecovery,
            hormonalCircadian = stressMask.hormonalCircadian,
        )
        return publishFromParts(
            cache = cache.copy(hypothesisState = hypothesisState),
            latentState = latentState,
            hypothesisState = hypothesisState,
            contextSnapshot = contextSnapshot,
            healthSnapshot = healthSnapshot,
            nowMs = nowMs,
        )
    }

    fun refreshFromContextIntents(contextSnapshot: ContextSnapshot?, nowMs: Long): PatientRuntimeSnapshot? {
        val cache = PatientStateRuntimeRepository.getLoopCache()
        if (cache == null) {
            return publishFromContextOnly(contextSnapshot, nowMs)
        }
        val healthSnapshot = PatientStateRuntimeRepository.getLatest()?.physioLive?.toHealthSnapshot(nowMs)
            ?: return publishFromContextOnly(contextSnapshot, nowMs)
        return rebuildAndPublish(
            cache = cache,
            healthSnapshot = healthSnapshot,
            contextSnapshot = contextSnapshot,
            nowMs = nowMs,
        )
    }

    fun refreshFromHealthSnapshot(healthSnapshot: HealthContextSnapshot, nowMs: Long): PatientRuntimeSnapshot? {
        val cache = PatientStateRuntimeRepository.getLoopCache() ?: return null
        val contextSnapshot = PatientStateRuntimeRepository.getLatest()?.let { cache.contextSnapshot }
        return rebuildAndPublish(
            cache = cache,
            healthSnapshot = healthSnapshot,
            contextSnapshot = contextSnapshot,
            nowMs = nowMs,
        )
    }

    private fun publishFromContextOnly(contextSnapshot: ContextSnapshot?, nowMs: Long): PatientRuntimeSnapshot? {
        if (contextSnapshot == null || contextSnapshot.intentCount <= 0) {
            return null
        }
        val patientState = PatientStateEngine.build(
            timestampMs = nowMs,
            phaseOutput = null,
            mealAbsorptionOutput = null,
            patternSnapshot = null,
            latentState = null,
            hypothesisState = null,
            contextSnapshot = contextSnapshot,
        )
        val patientModeDecision = PatientModeOrchestrator.evaluate(patientState)
        // Cascade native (R1): keep a full tree even on context-only refresh (thinner inputs OK).
        val physiologicalTree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            physioLive = PhysioLiveDigest(),
            timestampMs = nowMs,
        )
        val prior = PatientStateRuntimeRepository.getLatest()
        val harmoniaDecision = HarmoniaDecisionEngine.evaluate(
            tree = physiologicalTree,
            environment = prior?.harmoniaDecision?.environment,
            timestampMs = nowMs,
        )
        val runtimeSnapshot = PatientRuntimeSnapshot(
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            updatedAtMs = nowMs,
            physioLive = PhysioLiveDigest(),
            physiologicalTree = physiologicalTree,
            harmoniaDecision = harmoniaDecision,
            refreshSource = PatientRefreshSource.CONTEXT_INTENT,
        )
        PatientStateRuntimeRepository.publishRuntime(runtimeSnapshot, loopCache = null)
        return runtimeSnapshot
    }

    private fun publishFromParts(
        cache: PatientStateLoopCache,
        latentState: PhysioLatentState,
        hypothesisState: UamHypothesisState,
        contextSnapshot: ContextSnapshot?,
        healthSnapshot: HealthContextSnapshot,
        nowMs: Long,
    ): PatientRuntimeSnapshot {
        val patientState = PatientStateEngine.build(
            timestampMs = nowMs,
            phaseOutput = cache.phaseOutput,
            mealAbsorptionOutput = cache.mealAbsorptionOutput,
            patternSnapshot = cache.patternSnapshot,
            latentState = latentState,
            hypothesisState = hypothesisState,
            contextSnapshot = contextSnapshot,
            thermalBelief = healthSnapshot.thermalBelief,
        )
        val patientModeDecision = PatientModeOrchestrator.evaluate(patientState)
        val physioLive = PhysioLiveDigest.from(healthSnapshot, nowMs)
        // Always build — do not depend on a prior loop-published tree (chicken-egg killed the cascade).
        val physiologicalTree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            physioLive = physioLive,
            thermalBelief = healthSnapshot.thermalBelief,
            timestampMs = nowMs,
        )
        val harmoniaDecision = HarmoniaDecisionEngine.evaluate(
            tree = physiologicalTree,
            environment = PatientStateRuntimeRepository.getLatest()?.harmoniaDecision?.environment,
            timestampMs = nowMs,
        )
        val runtimeSnapshot = PatientRuntimeSnapshot(
            patientState = patientState,
            patientModeDecision = patientModeDecision,
            updatedAtMs = nowMs,
            physioLive = physioLive,
            thermalBelief = healthSnapshot.thermalBelief,
            physiologicalTree = physiologicalTree,
            harmoniaDecision = harmoniaDecision,
            refreshSource = PatientRefreshSource.PHYSIO_SIGNAL,
        )
        PatientStateRuntimeRepository.publishRuntime(runtimeSnapshot, loopCache = cache)
        return runtimeSnapshot
    }
}

internal enum class PatientRefreshSource {
    LOOP_TICK,
    PHYSIO_SIGNAL,
    CONTEXT_INTENT,
}

private fun PhysioLiveDigest.toHealthSnapshot(nowMs: Long): HealthContextSnapshot =
    HealthContextSnapshot(
        stepsLast15m = stepsLast15m,
        stepsLast60m = stepsLast60m,
        hrNow = hrNowBpm,
        hrAvg15m = hrAvg15mBpm,
        rhrResting = rhrRestingBpm,
        activityState = activityState,
        sleepDebtMinutes = sleepDebtMinutes,
        timestamp = (nowMs - snapshotAgeMs).coerceAtLeast(0L),
        confidence = confidence,
        source = source,
        isValid = confidence > 0.3,
    )
