package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.PhysiologicalStressMaskBuilder
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioDecisionTraceMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentStateBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisStateBuilder
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisTuning
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTpo
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate

/**
 * Android reads of the latent physio state.
 * Each method is the call that already existed at that line.
 * Effort belief and the TPO session stay Android. The patient runtime is the common function.
 */
internal interface AimiPhysioLatentCalls {
    fun physioContext(): PhysioContextMTR?
    fun physioTrace(): PhysioDecisionTraceMTR?
    fun phaseOutput(): PhysiologicalPhaseClassifier.Output?
    fun mealAbsorption(): MealAbsorptionPhaseEngine.Output?
    fun aggression(): CorrectionAggressionGate.Decision?
    fun uamConfidence(): Double
    fun behaviorProfile(): UamHypothesisTuning?
    fun inflammation(): InflammationAdjuster.InflammationResult?
    fun writeHypothesis(state: UamHypothesisState)
    fun writeLatent(state: PhysioLatentState)
    fun refreshEffort()
    fun nowMs(): Long
    fun contextSnapshot(): ContextSnapshot?
    fun refreshPatient(
        nowMs: Long,
        contextSnapshot: ContextSnapshot?,
        healthSnapshot: HealthContextSnapshot,
        sourceSensor: SourceSensor?,
    )
    fun tpoOrNull(): AimiTpo?
    fun patientState(): PatientStateSnapshot?
    fun patientMode(): PatientModeOrchestrator.Decision?
    fun bg(): Double
    fun delta(): Double
    fun cob(): Double
    fun minBgLookback(): Double
    fun writeMaxSmb(value: Double)
    fun maxSmb(): Double
    fun writeMaxSmbHb(value: Double)
}

internal fun decideUpdatePhysioLatentState(
    snapshot: HealthContextSnapshot,
    sourceSensor: SourceSensor?,
    patternSnapshot: PhysiologicalPatternSnapshot?,
    preferences: Preferences,
    calls: AimiPhysioLatentCalls,
): PhysioLatentState {
    val physioContext = calls.physioContext()
    val physioTrace = calls.physioTrace()
    val phaseOutput = calls.phaseOutput()
    val mealAbsorption = calls.mealAbsorption()
    val aggression = calls.aggression()
    val hypothesisState = UamHypothesisStateBuilder.build(
        phaseOutput = phaseOutput,
        mealAbsorptionOutput = mealAbsorption,
        patternSnapshot = patternSnapshot,
        correctionAggressionDecision = aggression,
        uamConfidence = calls.uamConfidence(),
        behaviorProfile = calls.behaviorProfile(),
    )
    val stressMask = PhysiologicalStressMaskBuilder.build(
        snapshot = snapshot,
        physioContext = physioContext,
        physioTrace = physioTrace,
        phaseOutput = phaseOutput,
        patternSnapshot = patternSnapshot,
        correctionAggressionDecision = aggression,
        chronicInflammation = calls.inflammation(),
    )
    val latentState = PhysioLatentStateBuilder.build(
        snapshot = snapshot,
        sourceSensor = sourceSensor,
        phaseOutput = phaseOutput,
        mealAbsorptionOutput = mealAbsorption,
        hypothesisState = hypothesisState,
        patternSnapshot = patternSnapshot,
        physioContext = physioContext,
        physioTrace = physioTrace,
        correctionAggressionDecision = aggression,
        chronicInflammation = calls.inflammation(),
        autonomicStress = stressMask.autonomicStress,
        inflammationRecovery = stressMask.inflammationRecovery,
        hormonalCircadian = stressMask.hormonalCircadian,
        cgmFirstSensorConfidence = preferences.get(BooleanKey.OApsAIMISensorConfidenceCgmFirst),
    )
    calls.writeHypothesis(hypothesisState)
    calls.writeLatent(latentState)
    // Effort belief is computed here, before the basal decision, so its posture can cut SMB and basal.
    calls.refreshEffort()
    calls.refreshPatient(
        nowMs = calls.nowMs(),
        contextSnapshot = calls.contextSnapshot(),
        healthSnapshot = snapshot,
        sourceSensor = sourceSensor,
    )
    val tpo = calls.tpoOrNull()
    if (tpo != null) {
        val patientState = calls.patientState()
        val patientMode = calls.patientMode()
        if (patientState != null && patientMode != null) {
            tpo.onPatientStateReady(
                patientState = patientState,
                patientModeName = patientMode.mode.name,
                patientModeConfidence = patientMode.confidence,
                correctionAggressionDecision = calls.aggression(),
                bgMgdl = calls.bg(),
                deltaMgdl5m = calls.delta(),
                cobGrams = calls.cob(),
                minBgLookback75m = calls.minBgLookback(),
                nowMs = calls.nowMs(),
            )
            if (tpo.consumePrefsChangedThisTick()) {
                val maxSmb = preferences.get(DoubleKey.OApsAIMIMaxSMB)
                calls.writeMaxSmb(maxSmb)
                calls.writeMaxSmbHb(
                    preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB).coerceAtLeast(calls.maxSmb()),
                )
            }
        }
    }
    return latentState
}
