package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoThresholdMath
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext

data class AimiRiskEnvelope(
    val phase: AimiRiskPhase,
    val bgNowMgdl: Double,
    val deltaMgdlPer5: Float,
    val predTerminalMgdl: Double,
    val eventualTerminalMgdl: Double,
    val pkpdEventualMgdl: Double? = null,
    val scenarioFloorTerminalMgdl: Double? = null,
    val scenarioBestTerminalMgdl: Double? = null,
    val decisionSource: DecisionPredictionSource = DecisionPredictionSource.PKPD_ONLY,
    val scenarioUpliftApplied: Boolean = false,
    val falseMealSuppression: Boolean = false,
    val decisionReason: String? = null,
    val pathMinRawMgdl: Double?,
    val pathMinClampedMgdl: Double?,
    val pathMinHitNumericFloor: Boolean,
    val compositeMinMgdl: Double,
    val hypoThresholdMgdl: Double,
    val aapsIobUnits: Double,
    val pkpdIobUnits: Double?,
    val iobDecisionUnits: Double,
    val iobSource: IobDecisionSource,
    val naiveEbgSignGuardApplied: Boolean = false,
)

object AimiRiskEnvelopeBuilder {

    fun buildEarly(
        bg: Double,
        delta: Float,
        predTerminal: Double,
        eventualTerminal: Double,
        predBGs: Predictions?,
        lgsThreshold: Int?,
    ): AimiRiskEnvelope {
        val path = PredictionPathMath.boundsFromPredictions(predBGs)
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = predTerminal,
            eventualTerminal = eventualTerminal,
        )
        val hypoTh = HypoThresholdMath.computeHypoThreshold(composite, lgsThreshold)
        return AimiRiskEnvelope(
            phase = AimiRiskPhase.EARLY,
            bgNowMgdl = bg,
            deltaMgdlPer5 = delta,
            predTerminalMgdl = predTerminal,
            eventualTerminalMgdl = eventualTerminal,
            pkpdEventualMgdl = eventualTerminal,
            scenarioFloorTerminalMgdl = null,
            scenarioBestTerminalMgdl = null,
            decisionSource = DecisionPredictionSource.PKPD_ONLY,
            scenarioUpliftApplied = false,
            falseMealSuppression = false,
            decisionReason = null,
            pathMinRawMgdl = path.pathMinRawMgdl,
            pathMinClampedMgdl = path.pathMinClampedMgdl,
            pathMinHitNumericFloor = path.pathMinHitNumericFloor,
            compositeMinMgdl = composite,
            hypoThresholdMgdl = hypoTh,
            aapsIobUnits = Double.NaN,
            pkpdIobUnits = null,
            iobDecisionUnits = Double.NaN,
            iobSource = IobDecisionSource.AAPS_DEFAULT,
        )
    }

    fun buildDecision(
        bg: Double,
        delta: Float,
        predTerminal: Double,
        eventualTerminal: Double,
        pathBounds: PredictionPathBounds,
        aapsIobUnits: Double,
        iobConsensus: IobConsensusResult,
        lgsThreshold: Int?,
        naiveEbgSignGuardApplied: Boolean,
        predictionAuthority: DecisionPredictionAuthority? = null,
        mealSafetyContext: MealSafetyContext = MealSafetyContext(),
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        targetBgMgdl: Double = 100.0,
        minBgLookback75m: Double = Double.MAX_VALUE,
        hasIndependentMealEvidence: Boolean = true,
        mealCertainty: MealCertainty? = null,
    ): AimiRiskEnvelope {
        val predForDecision = predictionAuthority?.predTerminalMgdl ?: predTerminal
        val eventualForDecision = predictionAuthority?.eventualTerminalMgdl ?: eventualTerminal
        val (adjPred, adjEventual) = SafetyPredictionTerminalsResolver.adjustForDecisionEnvelope(
            bg = bg,
            delta = delta,
            predForDecision = predForDecision,
            eventualForDecision = eventualForDecision,
            predictionAuthority = predictionAuthority,
            mealContext = mealSafetyContext,
            mealAbsorptionPhase = mealAbsorptionPhase,
            targetBgMgdl = targetBgMgdl,
            minBgLookback75m = minBgLookback75m,
            hasIndependentMealEvidence = hasIndependentMealEvidence,
            mealCertainty = mealCertainty,
        )
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = adjPred,
            eventualTerminal = adjEventual,
        )
        val hypoTh = HypoThresholdMath.computeHypoThreshold(composite, lgsThreshold)
        return AimiRiskEnvelope(
            phase = AimiRiskPhase.DECISION,
            bgNowMgdl = bg,
            deltaMgdlPer5 = delta,
            predTerminalMgdl = adjPred,
            eventualTerminalMgdl = adjEventual,
            pkpdEventualMgdl = predictionAuthority?.pkpdEventualMgdl ?: eventualTerminal,
            scenarioFloorTerminalMgdl = predictionAuthority?.scenarioFloorTerminalMgdl,
            scenarioBestTerminalMgdl = predictionAuthority?.scenarioBestTerminalMgdl,
            decisionSource = predictionAuthority?.source ?: DecisionPredictionSource.PKPD_ONLY,
            scenarioUpliftApplied = predictionAuthority?.scenarioUpliftApplied == true,
            falseMealSuppression = predictionAuthority?.falseMealSuppression == true,
            decisionReason = predictionAuthority?.reason,
            pathMinRawMgdl = pathBounds.pathMinRawMgdl,
            pathMinClampedMgdl = pathBounds.pathMinClampedMgdl,
            pathMinHitNumericFloor = pathBounds.pathMinHitNumericFloor,
            compositeMinMgdl = composite,
            hypoThresholdMgdl = hypoTh,
            aapsIobUnits = aapsIobUnits,
            pkpdIobUnits = iobConsensus.pkpdIobUnits,
            iobDecisionUnits = iobConsensus.decisionIobUnits,
            iobSource = iobConsensus.source,
            naiveEbgSignGuardApplied = naiveEbgSignGuardApplied,
        )
    }

    fun formatLogLine(envelope: AimiRiskEnvelope): String {
        val tag = when (envelope.phase) {
            AimiRiskPhase.EARLY -> "RISK_EARLY"
            AimiRiskPhase.DECISION -> "RISK_DECISION"
        }
        val pathRaw = envelope.pathMinRawMgdl?.let { aimiFmt0(it) } ?: "n/a"
        val pathClamp = envelope.pathMinClampedMgdl?.let { aimiFmt0(it) } ?: "n/a"
        val iobPart =
            if (envelope.phase == AimiRiskPhase.DECISION) {
                " iob=${aimiFmt2(envelope.aapsIobUnits)}→${aimiFmt2(envelope.iobDecisionUnits)}" +
                    "(${envelope.iobSource.name})" +
                    " src=${envelope.decisionSource.name}" +
                    envelope.pkpdEventualMgdl?.let { " pkpd=${aimiFmt0(it)}" }.orEmpty() +
                    envelope.scenarioBestTerminalMgdl?.let { " best=${aimiFmt0(it)}" }.orEmpty()
            } else {
                ""
            }
        val floorFlag = if (envelope.pathMinHitNumericFloor) " floorHit=1" else ""
        val mealFlag =
            if (envelope.phase == AimiRiskPhase.DECISION && envelope.falseMealSuppression) {
                " mealSupp=1"
            } else {
                ""
            }
        val upliftFlag =
            if (envelope.phase == AimiRiskPhase.DECISION && envelope.scenarioUpliftApplied) {
                " uplift=1"
            } else {
                ""
            }
        return "$tag: compositeMin=${envelope.compositeMinMgdl.toInt()} hypoTh=${envelope.hypoThresholdMgdl.toInt()} " +
            "predT=${envelope.predTerminalMgdl.toInt()} evT=${envelope.eventualTerminalMgdl.toInt()} " +
            "pathRaw=$pathRaw pathClamp=$pathClamp$floorFlag$mealFlag$upliftFlag$iobPart"
    }
}
