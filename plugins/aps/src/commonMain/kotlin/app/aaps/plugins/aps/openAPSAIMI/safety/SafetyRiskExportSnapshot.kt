package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskPhase

/**
 * Immutable safety + risk snapshot for one tick (Phase 4C export bridge, Phase 5 JSONL).
 * Built at [trySafetyStart] time (EARLY phase); reconciled against DECISION envelope at export.
 */
data class SafetyRiskExportSnapshot(
    val phase: AimiRiskPhase = AimiRiskPhase.EARLY,
    val predictiveHypoSuppressed: Boolean,
    val safetyGate: String,
    val haltRemainingPipeline: Boolean,
    val mealContextActive: Boolean,
    val mealRiseConfirmed: Boolean,
    val compositeMinMgdl: Double,
    val predBgMgdl: Double,
    val eventualBgMgdl: Double,
    val uamTerminalMgdl: Double?,
    val hypoThresholdMgdl: Double,
    val decisionCompositeMinMgdl: Double? = null,
    val decisionHypoThresholdMgdl: Double? = null,
    val reconcileDeltaMgdl: Double? = null,
)
