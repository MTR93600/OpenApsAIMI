package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionContext
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionEngine
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoProjectionCap
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoConstants

/**
 * Meal-aware safety composite for [resolveSafetyStart] (Phase 4B).
 * Replaces insulin-only eventual floor artefact with UAM terminal when meal rise is confirmed.
 */
data class SafetyPredictionTerminals(
    val predBg: Double,
    val eventualBg: Double,
    val uamTerminalMgdl: Double?,
    val mealRiseConfirmed: Boolean,
    val compositeMinMgdl: Double,
)

object SafetyPredictionTerminalsResolver {

    fun resolve(
        bg: Double,
        delta: Float,
        sanityPred: Double,
        sanityEventual: Double,
        uamTerminal: Double?,
        mealContext: MealSafetyContext,
        targetBgMgdl: Double = 100.0,
        minBgLookback75m: Double = Double.MAX_VALUE,
        hasIndependentMealEvidence: Boolean = true,
        cobG: Double = 0.0,
        mealCertainty: MealCertainty? = null,
    ): SafetyPredictionTerminals {
        val mealRiseConfirmed = isMealRiseConfirmed(
            bg, delta, mealContext, cobG = cobG, mealCertainty = mealCertainty,
        )
        val (adjPred, adjEventual) = adjustTerminals(
            bg = bg,
            delta = delta,
            pred = sanityPred,
            eventual = sanityEventual,
            uamTerminal = uamTerminal,
            mealRiseConfirmed = mealRiseConfirmed,
            targetBgMgdl = targetBgMgdl,
            minBgLookback75m = minBgLookback75m,
            hasIndependentMealEvidence = hasIndependentMealEvidence,
        )
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = adjPred,
            eventualTerminal = adjEventual,
        )
        return SafetyPredictionTerminals(
            predBg = adjPred,
            eventualBg = adjEventual,
            uamTerminalMgdl = uamTerminal,
            mealRiseConfirmed = mealRiseConfirmed,
            compositeMinMgdl = composite,
        )
    }

    /**
     * Preferred path when [ScenarioProjectionPair] is available — floor for safety, best for meal-rise uplift.
     */
    fun resolveFromScenario(
        bg: Double,
        delta: Float,
        mealContext: MealSafetyContext,
        projection: ScenarioProjectionPair,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        targetBgMgdl: Double = 100.0,
        minBgLookback75m: Double = Double.MAX_VALUE,
        hasIndependentMealEvidence: Boolean = true,
        cobG: Double = 0.0,
        mealCertainty: MealCertainty? = null,
    ): SafetyPredictionTerminals {
        val floor = projection.clinicalFloor
        val best = projection.scenarioBest
        val scenarioCtx = ScenarioProjectionContext(mealContext = mealContext)
        val mealRiseConfirmed = isMealRiseConfirmed(
            bg, delta, mealContext, mealAbsorptionPhase, cobG, mealCertainty,
        ) ||
            (
                mealCertainty == null &&
                    ScenarioProjectionEngine.isMealRiseConfirmed(bg = bg, delta = delta, ctx = scenarioCtx)
                )
        val floorPred = minOf(floor.pathMinMgdl, floor.terminalMgdl)
        val (adjPred, adjEventual) = adjustTerminals(
            bg = bg,
            delta = delta,
            pred = floorPred,
            eventual = floor.terminalMgdl,
            uamTerminal = best.terminalMgdl,
            mealRiseConfirmed = mealRiseConfirmed,
            targetBgMgdl = targetBgMgdl,
            minBgLookback75m = minBgLookback75m,
            hasIndependentMealEvidence = hasIndependentMealEvidence,
        )
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = bg,
            predTerminal = adjPred,
            eventualTerminal = adjEventual,
        )
        return SafetyPredictionTerminals(
            predBg = adjPred,
            eventualBg = adjEventual,
            uamTerminalMgdl = best.terminalMgdl,
            mealRiseConfirmed = mealRiseConfirmed,
            compositeMinMgdl = composite,
        )
    }

    /**
     * Meal-rise confirmation for safety terminal uplift.
     * Cascade D3: MealCertainty MED/HIGH is authoritative confirmation.
     * NONE/LOW fall through to legacy geometry (still desticky: falling never confirms via phase alone).
     */
    internal fun isMealRiseConfirmed(
        bg: Double,
        delta: Float,
        mealContext: MealSafetyContext,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        cobG: Double = 0.0,
        mealCertainty: MealCertainty? = null,
    ): Boolean {
        if (mealCertainty?.supportsMealSupport == true) return true
        if (!delta.isFinite() || delta < 0f) return false // falling never confirms
        // Active absorption / declared meal: require a non-falling rise (de-sticky vs phase-alone).
        if (mealAbsorptionPhase.isActive &&
            delta >= HarmoniaDecisionEngine.H4_MIN_RISING_DELTA_MGDL.toFloat()
        ) {
            return true
        }
        if (mealContext.hasMealIntent && delta >= 0.5f) return true
        val rising = delta >= PredictiveHypoConstants.RISING_MODERATE_DELTA.toFloat() && bg >= 90.0
        if (!rising) return false
        if (cobG <= 0.0 && bg in 95.0..140.0 && delta < 4.0f) return false
        return true
    }

    internal fun adjustTerminals(
        bg: Double,
        delta: Float,
        pred: Double,
        eventual: Double,
        uamTerminal: Double?,
        mealRiseConfirmed: Boolean,
        targetBgMgdl: Double = 100.0,
        minBgLookback75m: Double = Double.MAX_VALUE,
        hasIndependentMealEvidence: Boolean = true,
    ): Pair<Double, Double> {
        if (!mealRiseConfirmed || uamTerminal == null || !uamTerminal.isFinite()) {
            return applyPostHypoProjectionCap(
                bg = bg,
                delta = delta,
                pred = pred,
                eventual = eventual,
                targetBgMgdl = targetBgMgdl,
                minBgLookback75m = minBgLookback75m,
                hasIndependentMealEvidence = hasIndependentMealEvidence,
            )
        }
        var adjPred = pred
        var adjEventual = eventual
        val floorArtefact = eventual <= AimiRiskConstants.NUMERIC_FLOOR_MGDL + 1.0
        if (floorArtefact && uamTerminal > eventual) {
            adjEventual = uamTerminal
        } else if (uamTerminal > eventual + 15.0 && bg > eventual + 20.0) {
            adjEventual = uamTerminal
        }
        if (adjPred < bg - 40.0 && uamTerminal > adjPred && delta >= 0f) {
            val upliftCap = bg + delta.toDouble() * 8.0
            adjPred = maxOf(adjPred, minOf(uamTerminal, upliftCap))
        }
        return applyPostHypoProjectionCap(
            bg = bg,
            delta = delta,
            pred = adjPred,
            eventual = adjEventual,
            targetBgMgdl = targetBgMgdl,
            minBgLookback75m = minBgLookback75m,
            hasIndependentMealEvidence = hasIndependentMealEvidence,
        )
    }

    private fun applyPostHypoProjectionCap(
        bg: Double,
        delta: Float,
        pred: Double,
        eventual: Double,
        targetBgMgdl: Double,
        minBgLookback75m: Double,
        hasIndependentMealEvidence: Boolean,
    ): Pair<Double, Double> {
        val eventualCap = PostHypoProjectionCap.capTerminalMgdl(
            bgMgdl = bg,
            targetBgMgdl = targetBgMgdl,
            deltaMgdl5m = delta.toDouble(),
            terminalMgdl = eventual,
            minBgLookback75m = minBgLookback75m,
            hasIndependentMealEvidence = hasIndependentMealEvidence,
        )
        val predCap = PostHypoProjectionCap.capTerminalMgdl(
            bgMgdl = bg,
            targetBgMgdl = targetBgMgdl,
            deltaMgdl5m = delta.toDouble(),
            terminalMgdl = pred,
            minBgLookback75m = minBgLookback75m,
            hasIndependentMealEvidence = hasIndependentMealEvidence,
        )
        return predCap.cappedTerminalMgdl to eventualCap.cappedTerminalMgdl
    }

    /**
     * Meal-aware terminal adjustment for the DECISION risk envelope (SMB hypo-gate composite).
     * Reuses [adjustTerminals] so the DECISION composite stays consistent with the safety-start path.
     * Skips uplift when [predictionAuthority] flags false-meal suppression.
     */
    fun adjustForDecisionEnvelope(
        bg: Double,
        delta: Float,
        predForDecision: Double,
        eventualForDecision: Double,
        predictionAuthority: DecisionPredictionAuthority?,
        mealContext: MealSafetyContext,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        targetBgMgdl: Double = 100.0,
        minBgLookback75m: Double = Double.MAX_VALUE,
        hasIndependentMealEvidence: Boolean = true,
        cobG: Double = 0.0,
        mealCertainty: MealCertainty? = null,
    ): Pair<Double, Double> {
        val mealRiseConfirmed =
            predictionAuthority?.falseMealSuppression != true &&
                isMealRiseConfirmed(bg, delta, mealContext, mealAbsorptionPhase, cobG, mealCertainty)
        return adjustTerminals(
            bg = bg,
            delta = delta,
            pred = predForDecision,
            eventual = eventualForDecision,
            uamTerminal = predictionAuthority?.scenarioBestTerminalMgdl,
            mealRiseConfirmed = mealRiseConfirmed,
            targetBgMgdl = targetBgMgdl,
            minBgLookback75m = minBgLookback75m,
            hasIndependentMealEvidence = hasIndependentMealEvidence,
        )
    }
}
