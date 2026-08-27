package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId

enum class PatientMode {
    STABLE_BASELINE,
    FAST_MEAL,
    PROLONGED_MEAL,
    DAWN_ENDOGENOUS,
    POST_HYPO_RECOVERY,
    STRESS_RESISTANCE,
    EXERCISE_AFTERBURN,
    POOR_SLEEP_DAY,
    ABSORPTION_UNCERTAIN,
}

enum class PatientStrategyHint {
    BASELINE_BALANCE,
    SMB_PRIORITY,
    MEAL_SUPPORT,
    BASAL_BRIDGE,
    CONSERVATIVE_OBSERVE,
    HYPO_RECOVERY,
    PKPD_REASSESS,
}

internal object PatientModeOrchestrator {

    data class Decision(
        val mode: PatientMode,
        val confidence: Double,
        val strategyHint: PatientStrategyHint,
        val mealBias: Double,
        val protectionBias: Double,
        val userIntentConfidence: Double,
        val reasonCodes: List<String>,
        val source: String = "patient_mode_v2",
    ) {
        fun toJsonObject(): JsonObject =
            buildJsonObject {
                put("mode", mode.name)
                put("confidence", confidence)
                put("strategy_hint", strategyHint.name)
                put("meal_bias", mealBias)
                put("protection_bias", protectionBias)
                put("user_intent_confidence", userIntentConfidence)
                put("reason_codes", JsonArray(reasonCodes.map { JsonPrimitive(it) }))
                put("source", source)
            }

        fun summary(): String =
            "mode=${mode.name} conf=${fmt(confidence)} strat=${strategyHint.name} " +
                "mealBias=${fmt(mealBias)} protect=${fmt(protectionBias)} " +
                "reasons=${reasonCodes.joinToString(",")}"

        private fun fmt(value: Double): String = aimiFmt2(value)
    }

    fun evaluate(state: PatientStateSnapshot): Decision {
        val reasons = linkedSetOf<String>()
        val causal = state.causalPosterior

        if (causal.dominant == CausalStateId.POST_HYPO_RECOVERY && causal.dominantConfidence >= 0.65) {
            reasons += "CAUSAL_POST_HYPO"
            if (state.userIntent.hasAlcohol) reasons += "CTX_ALCOHOL"
            return decision(
                mode = PatientMode.POST_HYPO_RECOVERY,
                confidence = maxOf(causal.dominantConfidence, causal.protectiveConfidence, state.userIntent.avgConfidence),
                strategyHint = PatientStrategyHint.HYPO_RECOVERY,
                mealBias = 0.08,
                protectionBias = 0.94,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.postHypoReboundProb >= 0.72 ||
            (state.uamDominant == UamHypothesisId.POST_HYPO && state.uamDominantConfidence >= 0.65) ||
            state.userIntent.hasAlcohol
        ) {
            if (state.postHypoReboundProb >= 0.72) reasons += "LATENT_POST_HYPO"
            if (state.uamDominant == UamHypothesisId.POST_HYPO) reasons += "UAM_POST_HYPO"
            if (state.userIntent.hasAlcohol) reasons += "CTX_ALCOHOL"
            return decision(
                mode = PatientMode.POST_HYPO_RECOVERY,
                confidence = maxOf(
                    state.postHypoReboundProb,
                    state.uamDominantConfidence,
                    state.userIntent.avgConfidence,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.HYPO_RECOVERY,
                mealBias = 0.08,
                protectionBias = 0.92,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (shouldPreemptMealWithProtectiveContext(state, causal)) {
            reasons += "FALSE_MEAL_SUPPRESS"
            reasons += "PROTECTIVE_PREEMPT"
            return when (causal.dominant) {
                CausalStateId.DAWN_ENDOGENOUS -> {
                    reasons += "CAUSAL_DAWN_ENDOGENOUS"
                    decision(
                        mode = PatientMode.DAWN_ENDOGENOUS,
                        confidence = maxOf(
                            causal.dominantConfidence,
                            causal.protectiveConfidence,
                            state.endogenousGlucoseDrive,
                        ),
                        strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                        mealBias = 0.14,
                        protectionBias = 0.84,
                        state = state,
                        reasonCodes = reasons,
                    )
                }

                CausalStateId.STRESS_RESISTANCE, CausalStateId.INFLAMMATORY_DRIFT -> {
                    if (causal.dominant == CausalStateId.INFLAMMATORY_DRIFT) reasons += "CAUSAL_INFLAMMATORY_DRIFT"
                    if (causal.dominant == CausalStateId.STRESS_RESISTANCE) reasons += "CAUSAL_STRESS_RESISTANCE"
                    decision(
                        mode = PatientMode.STRESS_RESISTANCE,
                        confidence = maxOf(
                            causal.dominantConfidence,
                            causal.protectiveConfidence,
                            state.transientResistanceProb,
                            state.thermalInflammationIndex,
                        ),
                        strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                        mealBias = 0.20,
                        protectionBias = 0.82,
                        state = state,
                        reasonCodes = reasons,
                    )
                }

                else -> {
                    reasons += "CAUSAL_ABSORPTION_UNCERTAIN"
                    decision(
                        mode = PatientMode.ABSORPTION_UNCERTAIN,
                        confidence = maxOf(
                            causal.dominantConfidence,
                            causal.protectiveConfidence,
                            state.eventMemory.correctionFragilityScore,
                            1.0 - state.sensorConfidence,
                        ),
                        strategyHint = PatientStrategyHint.PKPD_REASSESS,
                        mealBias = 0.28,
                        protectionBias = 0.88,
                        state = state,
                        reasonCodes = reasons,
                    )
                }
            }
        }

        if (causal.dominant == CausalStateId.FAST_MEAL && causal.dominantConfidence >= 0.68) {
            reasons += "CAUSAL_FAST_MEAL"
            if (state.userIntent.hasMealRisk) reasons += "CTX_MEAL_RISK"
            return decision(
                mode = PatientMode.FAST_MEAL,
                confidence = maxOf(causal.dominantConfidence, causal.mealConfidence, state.mealAbsorptionBelief),
                strategyHint = PatientStrategyHint.SMB_PRIORITY,
                mealBias = 0.92,
                protectionBias = 0.16,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.mealAbsorptionPhase == MealAbsorptionPhase.FIRST_WAVE ||
            state.mealAbsorptionPhase == MealAbsorptionPhase.PEAK_CORRECTION ||
            (state.uamDominant == UamHypothesisId.MEAL && state.mealProb >= 0.72)
        ) {
            if (state.mealAbsorptionPhase == MealAbsorptionPhase.FIRST_WAVE) reasons += "MEAL_FIRST_WAVE"
            if (state.mealAbsorptionPhase == MealAbsorptionPhase.PEAK_CORRECTION) reasons += "MEAL_PEAK_CORRECTION"
            if (state.uamDominant == UamHypothesisId.MEAL) reasons += "UAM_MEAL"
            if (state.userIntent.hasMealRisk) reasons += "CTX_MEAL_RISK"
            return decision(
                mode = PatientMode.FAST_MEAL,
                confidence = maxOf(
                    state.mealAbsorptionBelief,
                    state.mealProb,
                    state.uamDominantConfidence,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.SMB_PRIORITY,
                mealBias = 0.90,
                protectionBias = 0.18,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (causal.dominant == CausalStateId.PROLONGED_MEAL && causal.dominantConfidence >= 0.60) {
            reasons += "CAUSAL_PROLONGED_MEAL"
            return decision(
                mode = PatientMode.PROLONGED_MEAL,
                confidence = maxOf(causal.dominantConfidence, causal.mealConfidence, state.mealAbsorptionBelief),
                strategyHint = PatientStrategyHint.MEAL_SUPPORT,
                mealBias = 0.76,
                protectionBias = 0.28,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.mealAbsorptionPhase == MealAbsorptionPhase.SECOND_WAVE ||
            state.mealAbsorptionPhase == MealAbsorptionPhase.INTER_WAVE ||
            state.mealAbsorptionPhase == MealAbsorptionPhase.LATE_FAT ||
            state.uamDominant == UamHypothesisId.LATE_FAT
        ) {
            if (state.mealAbsorptionPhase != MealAbsorptionPhase.NONE) reasons += "MEAL_EXTENDED_PHASE"
            if (state.uamDominant == UamHypothesisId.LATE_FAT) reasons += "UAM_LATE_FAT"
            return decision(
                mode = PatientMode.PROLONGED_MEAL,
                confidence = maxOf(
                    state.mealAbsorptionBelief,
                    state.mealProb,
                    state.uamDominantConfidence,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.MEAL_SUPPORT,
                mealBias = 0.72,
                protectionBias = 0.30,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (causal.dominant == CausalStateId.DAWN_ENDOGENOUS && causal.dominantConfidence >= 0.62) {
            reasons += "CAUSAL_DAWN_ENDOGENOUS"
            if (state.falseMealSuppression) reasons += "FALSE_MEAL_SUPPRESS"
            return decision(
                mode = PatientMode.DAWN_ENDOGENOUS,
                confidence = maxOf(causal.dominantConfidence, causal.protectiveConfidence, state.phaseConfidence),
                strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                mealBias = 0.14,
                protectionBias = 0.82,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.endogenousGlucoseDrive >= 0.62 && state.mealProb < state.endogenousGlucoseDrive + 0.10) {
            reasons += "LATENT_ENDOGENOUS"
            if (state.falseMealSuppression) reasons += "FALSE_MEAL_SUPPRESS"
            return decision(
                mode = PatientMode.DAWN_ENDOGENOUS,
                confidence = maxOf(
                    state.endogenousGlucoseDrive,
                    state.phaseConfidence,
                    state.patternDominantConfidence,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.BASAL_BRIDGE,
                mealBias = 0.16,
                protectionBias = 0.78,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (causal.dominant == CausalStateId.EXERCISE_AFTERBURN && causal.dominantConfidence >= 0.58) {
            reasons += "CAUSAL_EXERCISE_AFTERBURN"
            if (state.userIntent.hasActivity) reasons += "CTX_ACTIVITY"
            return decision(
                mode = PatientMode.EXERCISE_AFTERBURN,
                confidence = maxOf(causal.dominantConfidence, causal.protectiveConfidence, state.userIntent.avgConfidence),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.16,
                protectionBias = 0.84,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.userIntent.hasActivity && state.userIntent.avgConfidence >= 0.55) {
            reasons += "CTX_ACTIVITY"
            return decision(
                mode = PatientMode.EXERCISE_AFTERBURN,
                confidence = maxOf(
                    state.userIntent.avgConfidence,
                    state.sensorConfidence,
                    0.58,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.18,
                protectionBias = 0.82,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (causal.dominant == CausalStateId.INFLAMMATORY_DRIFT && causal.dominantConfidence >= 0.60) {
            reasons += "CAUSAL_INFLAMMATORY_DRIFT"
            if (state.userIntent.hasIllness) reasons += "CTX_ILLNESS"
            return decision(
                mode = PatientMode.STRESS_RESISTANCE,
                confidence = maxOf(causal.dominantConfidence, causal.protectiveConfidence, state.thermalInflammationIndex),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.18,
                protectionBias = 0.84,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.thermalInflammationIndex >= 0.62 &&
            (state.userIntent.hasIllness || state.transientResistanceProb >= 0.55)
        ) {
            reasons += "THERMAL_INFLAMMATORY_DRIFT"
            if (state.userIntent.hasIllness) reasons += "CTX_ILLNESS"
            return decision(
                mode = PatientMode.STRESS_RESISTANCE,
                confidence = maxOf(
                    state.thermalInflammationIndex,
                    state.transientResistanceProb,
                    state.userIntent.avgConfidence,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.20,
                protectionBias = 0.80,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.thermalHypothesis == "CYCLE_BBT_RISE" && state.thermalInflammationIndex < 0.70) {
            reasons += "THERMAL_CYCLE_BBT"
            return decision(
                mode = PatientMode.STRESS_RESISTANCE,
                confidence = maxOf(state.thermalInflammationIndex, 0.58, causal.dominantConfidence),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.30,
                protectionBias = 0.62,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (causal.dominant == CausalStateId.STRESS_RESISTANCE && causal.dominantConfidence >= 0.64) {
            reasons += "CAUSAL_STRESS_RESISTANCE"
            if (state.userIntent.hasStress) reasons += "CTX_STRESS"
            if (state.userIntent.hasIllness) reasons += "CTX_ILLNESS"
            return decision(
                mode = PatientMode.STRESS_RESISTANCE,
                confidence = maxOf(causal.dominantConfidence, causal.protectiveConfidence, state.transientResistanceProb),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.22,
                protectionBias = 0.76,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.transientResistanceProb >= 0.70 &&
            (state.userIntent.hasStress || state.userIntent.hasIllness || state.uamDominant == UamHypothesisId.STRESS)
        ) {
            reasons += "LATENT_RESISTANCE"
            if (state.userIntent.hasStress) reasons += "CTX_STRESS"
            if (state.userIntent.hasIllness) reasons += "CTX_ILLNESS"
            if (state.uamDominant == UamHypothesisId.STRESS) reasons += "UAM_STRESS"
            return decision(
                mode = PatientMode.STRESS_RESISTANCE,
                confidence = maxOf(
                    state.transientResistanceProb,
                    state.uamDominantConfidence,
                    state.userIntent.avgConfidence,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.24,
                protectionBias = 0.72,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.thermalRecoveryBurden >= 0.60 && state.sleepDebtScore >= 0.45) {
            reasons += "THERMAL_RECOVERY_COOLING"
            reasons += "LATENT_SLEEP_DEBT"
            return decision(
                mode = PatientMode.POOR_SLEEP_DAY,
                confidence = maxOf(state.thermalRecoveryBurden, state.sleepDebtScore, 0.58, causal.dominantConfidence),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.26,
                protectionBias = 0.74,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.sleepDebtScore >= 0.60) {
            reasons += "LATENT_SLEEP_DEBT"
            return decision(
                mode = PatientMode.POOR_SLEEP_DAY,
                confidence = maxOf(state.sleepDebtScore, state.endogenousGlucoseDrive, 0.55, causal.dominantConfidence),
                strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                mealBias = 0.28,
                protectionBias = 0.68,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (causal.dominant == CausalStateId.ABSORPTION_UNCERTAIN && causal.dominantConfidence >= 0.55) {
            reasons += "CAUSAL_ABSORPTION_UNCERTAIN"
            if (state.falseMealSuppression) reasons += "FALSE_MEAL_SUPPRESS"
            return decision(
                mode = PatientMode.ABSORPTION_UNCERTAIN,
                confidence = maxOf(causal.dominantConfidence, causal.protectiveConfidence, 1.0 - state.sensorConfidence),
                strategyHint = PatientStrategyHint.PKPD_REASSESS,
                mealBias = 0.30,
                protectionBias = 0.86,
                state = state,
                reasonCodes = reasons,
            )
        }

        if (state.sensorConfidence < 0.55 ||
            (state.falseMealSuppression && state.mealProb in 0.30..0.65)
        ) {
            if (state.sensorConfidence < 0.55) reasons += "SENSOR_LOW"
            if (state.falseMealSuppression) reasons += "FALSE_MEAL_SUPPRESS"
            return decision(
                mode = PatientMode.ABSORPTION_UNCERTAIN,
                confidence = maxOf(
                    1.0 - state.sensorConfidence,
                    state.mealProb,
                    state.patternDominantConfidence,
                    causal.dominantConfidence,
                ),
                strategyHint = PatientStrategyHint.PKPD_REASSESS,
                mealBias = 0.32,
                protectionBias = 0.84,
                state = state,
                reasonCodes = reasons,
            )
        }

        reasons += "BASELINE"
        return decision(
            mode = PatientMode.STABLE_BASELINE,
            confidence = maxOf(
                state.sensorConfidence,
                0.50,
                if (causal.dominant != CausalStateId.UNKNOWN) causal.learningQuality * 0.85 else 0.0,
            ),
            strategyHint = PatientStrategyHint.BASELINE_BALANCE,
            mealBias = 0.45,
            protectionBias = 0.22,
            state = state,
            reasonCodes = reasons,
        )
    }

    private fun decision(
        mode: PatientMode,
        confidence: Double,
        strategyHint: PatientStrategyHint,
        mealBias: Double,
        protectionBias: Double,
        state: PatientStateSnapshot,
        reasonCodes: Set<String>,
    ): Decision =
        Decision(
            mode = mode,
            confidence = confidence.coerceIn(0.0, 1.0),
            strategyHint = strategyHint,
            mealBias = mealBias.coerceIn(0.0, 1.0),
            protectionBias = protectionBias.coerceIn(0.0, 1.0),
            userIntentConfidence = state.userIntent.avgConfidence.coerceIn(0.0, 1.0),
            reasonCodes = reasonCodes.ifEmpty { setOf("BASELINE") }.toList(),
            source = "patient_mode_v2",
        )

    private fun shouldPreemptMealWithProtectiveContext(
        state: PatientStateSnapshot,
        causal: CausalStatePosterior,
    ): Boolean {
        if (!state.falseMealSuppression) return false
        if (causal.protectiveConfidence < maxOf(causal.mealConfidence + 0.08, 0.62)) return false
        return causal.dominant in setOf(
            CausalStateId.DAWN_ENDOGENOUS,
            CausalStateId.STRESS_RESISTANCE,
            CausalStateId.INFLAMMATORY_DRIFT,
            CausalStateId.ABSORPTION_UNCERTAIN,
        )
    }
}
