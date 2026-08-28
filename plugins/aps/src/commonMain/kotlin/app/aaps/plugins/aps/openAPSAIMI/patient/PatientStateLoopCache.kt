package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.core.data.model.SourceSensor
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioDecisionTraceMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate

/**
 * Last loop-derived inputs used to refresh patient understanding when wearable signals move
 * between determine_basal ticks.
 */
internal data class PatientStateLoopCache(
    val phaseOutput: PhysiologicalPhaseClassifier.Output?,
    val mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
    val patternSnapshot: PhysiologicalPatternSnapshot?,
    val contextSnapshot: ContextSnapshot?,
    val sourceSensor: SourceSensor?,
    val correctionAggressionDecision: CorrectionAggressionGate.Decision?,
    val chronicInflammation: InflammationAdjuster.InflammationResult?,
    val physioContext: PhysioContextMTR?,
    val physioTrace: PhysioDecisionTraceMTR?,
    val hypothesisState: UamHypothesisState?,
    val uamConfidence: Double,
)
