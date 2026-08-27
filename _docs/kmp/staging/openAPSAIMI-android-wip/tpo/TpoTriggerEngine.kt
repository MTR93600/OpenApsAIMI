package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import kotlin.math.max

internal object TpoTriggerEngine {

    private const val COOLDOWN_MS = 30L * 60L * 1000L
    private const val RECENT_HYPO_LEDGER_WINDOW_MS = 6L * 60L * 60L * 1000L
    private const val RECENT_HYPO_MIN_BG_LOOKBACK_MGDL = 85.0

    data class Evaluation(
        val proposal: TpoProposal?,
        val blockedReason: String? = null,
    )

    fun evaluate(
        input: TpoTickInput,
        ledger: TpoEpisodeLedger,
        activePackId: TpoPackId?,
        lastRevertAtMsByPack: Map<TpoPackId, Long>,
    ): Evaluation {
        val candidates = buildList {
            evaluateExhausted(input, ledger)?.let { add(it) }
            evaluatePostHypo(input, ledger)?.let { add(it) }
            evaluatePoorSleep(input)?.let { add(it) }
        }.sortedByDescending { it.packId.priority }

        if (candidates.isEmpty()) {
            return Evaluation(proposal = null, blockedReason = "no_trigger")
        }

        val best = candidates.first()
        if (activePackId != null && activePackId.priority >= best.packId.priority) {
            return Evaluation(proposal = null, blockedReason = "active_session")
        }

        val lastRevert = lastRevertAtMsByPack[best.packId]
        if (lastRevert != null && input.nowMs - lastRevert < COOLDOWN_MS) {
            return Evaluation(proposal = null, blockedReason = "cooldown")
        }

        if (best.packId == TpoPackId.POST_HYPO_RECOVERY && isMealGuardBlocked(input)) {
            return Evaluation(proposal = null, blockedReason = "meal_guard")
        }
        if (best.packId == TpoPackId.POOR_SLEEP_WINDOW && isDawnGuardBlocked(input)) {
            return Evaluation(proposal = null, blockedReason = "dawn_guard")
        }

        return Evaluation(proposal = best)
    }

    private fun evaluatePostHypo(input: TpoTickInput, ledger: TpoEpisodeLedger): TpoProposal? {
        val reasons = mutableListOf<String>()
        var confidence = 0.0
        if (input.patientModeName == PatientMode.POST_HYPO_RECOVERY.name &&
            input.patientModeConfidence >= 0.65
        ) {
            reasons += "PATIENT_MODE_POST_HYPO"
            confidence = maxOf(confidence, input.patientModeConfidence)
        }
        if (input.causalDominantName == CausalStateId.POST_HYPO_RECOVERY.name &&
            input.causalDominantConfidence >= 0.65
        ) {
            reasons += "CAUSAL_POST_HYPO"
            confidence = maxOf(confidence, input.causalDominantConfidence)
        }
        if (input.reboundGuardActive) {
            reasons += "REBOUND_GUARD"
            confidence = maxOf(confidence, 0.78)
        }
        if (input.postHypoReboundProb >= 0.72) {
            reasons += "LATENT_POST_HYPO"
            confidence = maxOf(confidence, input.postHypoReboundProb)
        }
        if (confidence < 0.65 || reasons.isEmpty()) return null
        if (!hasActionableHypoContext(input, ledger)) return null
        val tier = when {
            input.reboundGuardActive && input.minBgLookback75m < 75.0 -> TuningStepTier.STRONG
            confidence >= 0.85 -> TuningStepTier.STRONG
            confidence >= 0.75 -> TuningStepTier.MODERATE
            else -> TuningStepTier.MICRO
        }
        return TpoProposal(
            packId = TpoPackId.POST_HYPO_RECOVERY,
            tier = tier,
            algoConfidence = confidence,
            reasonCodes = reasons,
        )
    }

    private fun hasActionableHypoContext(input: TpoTickInput, ledger: TpoEpisodeLedger): Boolean {
        if (input.reboundGuardActive) return true
        if (input.minBgLookback75m < RECENT_HYPO_MIN_BG_LOOKBACK_MGDL) return true
        return ledger.hasHypoEpisodeWithin(RECENT_HYPO_LEDGER_WINDOW_MS, input.nowMs)
    }

    private fun evaluatePoorSleep(input: TpoTickInput): TpoProposal? {
        val reasons = mutableListOf<String>()
        var confidence = 0.0
        if (input.patientModeName == PatientMode.POOR_SLEEP_DAY.name &&
            input.patientModeConfidence >= 0.55
        ) {
            reasons += "PATIENT_MODE_POOR_SLEEP"
            confidence = maxOf(confidence, input.patientModeConfidence)
        }
        if (input.sleepDebtScore >= 0.60) {
            reasons += "LATENT_SLEEP_DEBT"
            confidence = maxOf(confidence, input.sleepDebtScore)
        }
        if (input.thermalRecoveryBurden >= 0.60 && input.sleepDebtScore >= 0.45) {
            reasons += "THERMAL_RECOVERY_SLEEP"
            confidence = maxOf(confidence, max(input.thermalRecoveryBurden, input.sleepDebtScore))
        }
        if (confidence < 0.55 || reasons.isEmpty()) return null
        val tier = when {
            confidence >= 0.75 -> TuningStepTier.STRONG
            confidence >= 0.60 -> TuningStepTier.MODERATE
            else -> TuningStepTier.MICRO
        }
        return TpoProposal(
            packId = TpoPackId.POOR_SLEEP_WINDOW,
            tier = tier,
            algoConfidence = confidence,
            reasonCodes = reasons,
        )
    }

    private fun evaluateExhausted(
        input: TpoTickInput,
        ledger: TpoEpisodeLedger,
    ): TpoProposal? {
        val exhaustion = input.eventMemory.postHyperExhaustionScore
        val fragility = input.eventMemory.correctionFragilityScore
        val confidence = maxOf(exhaustion, fragility)
        if (confidence < 0.68) return null
        if (!ledger.hasHyperCrashWithin(windowMs = 4L * 60L * 60L * 1000L, nowMs = input.nowMs) &&
            exhaustion < 0.75
        ) {
            return null
        }
        val reasons = buildList {
            if (exhaustion >= 0.68) add("POST_HYPER_EXHAUSTION")
            if (fragility >= 0.70) add("CORRECTION_FRAGILITY")
            if (ledger.hasHyperCrashWithin(4L * 60L * 60L * 1000L, input.nowMs)) add("LEDGER_HYPER_CRASH")
        }
        val tier = if (confidence >= 0.80) TuningStepTier.STRONG else TuningStepTier.MODERATE
        return TpoProposal(
            packId = TpoPackId.EXHAUSTED_RECOVERY,
            tier = tier,
            algoConfidence = confidence,
            reasonCodes = reasons,
        )
    }

    /**
     * Blocks POST_HYPO overlay during meal absorption / rise even when patient mode is still
     * POST_HYPO_RECOVERY (orchestrator ranks post-hypo above FAST_MEAL).
     */
    private fun isMealGuardBlocked(input: TpoTickInput): Boolean {
        if (isExplicitMealPatientMode(input)) return true
        return isActiveMealRise(input)
    }

    private fun isExplicitMealPatientMode(input: TpoTickInput): Boolean =
        input.cobGrams >= 5.0 &&
            (
                input.patientModeName == PatientMode.FAST_MEAL.name ||
                    input.patientModeName == PatientMode.PROLONGED_MEAL.name
                ) &&
            input.patientModeConfidence >= 0.65

    private fun isActiveMealRise(input: TpoTickInput): Boolean {
        if (input.cobGrams < 3.0 || input.bgMgdl < 100.0) return false
        val rising = input.deltaMgdl5m >= 1.5 || input.mealProb >= 0.55
        if (!rising) return false
        return when {
            input.cobGrams >= 5.0 -> true
            input.mealProb >= 0.65 -> true
            input.patientModeName == PatientMode.FAST_MEAL.name -> true
            input.patientModeName == PatientMode.PROLONGED_MEAL.name -> true
            else -> false
        }
    }

    private fun isDawnGuardBlocked(input: TpoTickInput): Boolean =
        input.causalDominantName == CausalStateId.DAWN_ENDOGENOUS.name &&
            input.causalDominantConfidence >= 0.60
}
