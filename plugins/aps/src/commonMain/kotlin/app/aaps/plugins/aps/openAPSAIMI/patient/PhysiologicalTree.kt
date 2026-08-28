package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalHypothesis
import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleBelief
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class PhysiologicalTreeSnapshot(
    val timestamp: Long,
    val roots: PhysiologicalRoots,
    val trunk: PhysiologicalTrunk,
    val branches: PhysiologicalBranches,
    val leaves: PhysiologicalLeaves,
    val fruits: PhysiologicalFruits? = null,
    val seasons: PhysiologicalSeasons? = null,
    /** Raw live wearable signals (HR / steps) feeding the tree — exported as structured fields. */
    val physioLive: PhysioLiveDigest = PhysioLiveDigest(),
    val compactSummary: String,
    /**
     * Dose-path intent for Harmonia SMB arbitration (not a pump command).
     * Soft meal + confirmed rise → [InsulinIntent.NEED_MORE_INSULIN].
     */
    val insulinIntent: InsulinIntent = InsulinIntent.NONE,
    /** 0..1 urgency hint for NEED_MORE_INSULIN / MEAL_SUPPORT. */
    val insulinUrgency: Double = 0.0,
    val version: Int = 1,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("timestamp", timestamp)
            put("version", version)
            put("roots", roots.toJsonObject())
            put("trunk", trunk.toJsonObject())
            put("branches", branches.toJsonObject())
            put("leaves", leaves.toJsonObject())
            put("fruits", fruits?.toJsonObject() ?: JsonNull)
            put("seasons", seasons?.toJsonObject() ?: JsonNull)
            // Structured HR/steps (the dashboard refreshes these each tick) — previously only present
            // inside branches.activity.reasons strings, so invisible to field-level export consumers.
            put("physio_live", physioLive.toJsonObject())
            put("compact_summary", compactSummary)
            put("insulin_intent", insulinIntent.name)
            put("insulin_urgency", insulinUrgency)
            // Tree informs; Harmonia arbitrates basal + SMB within hard envelope (not "no authority").
            put("insulin_authority", "harmonia_basal_and_smb_arbitration")
            put("source", "harmonia_tree_v1")
        }
}

data class PhysiologicalRoots(
    val profileState: PhysiologicalSignalState,
    val tddState: PhysiologicalSignalState,
    val basalState: PhysiologicalSignalState,
    val isfState: PhysiologicalSignalState,
    val preferenceState: PhysiologicalSignalState,
    val historicalPatternState: PhysiologicalSignalState,
    val mlAsyncState: PhysiologicalSignalState,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("profile_state", profileState.toJsonObject())
            put("tdd_state", tddState.toJsonObject())
            put("basal_state", basalState.toJsonObject())
            put("isf_state", isfState.toJsonObject())
            put("preference_state", preferenceState.toJsonObject())
            put("historical_pattern_state", historicalPatternState.toJsonObject())
            put("ml_async_state", mlAsyncState.toJsonObject())
        }
}

data class PhysiologicalTrunk(
    val globalState: GlobalPhysiologicalState,
    val confidence: Double,
    val riskLevel: PhysiologicalRiskLevel,
    val dataCoherence: DataCoherenceLevel,
    val mainReason: String,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("global_state", globalState.name)
            put("confidence", confidence)
            put("risk_level", riskLevel.name)
            put("data_coherence", dataCoherence.name)
            put("main_reason", mainReason)
        }
}

data class PhysiologicalBranches(
    val digestion: PhysiologicalSignalState,
    val meal: PhysiologicalSignalState,
    val activity: PhysiologicalSignalState,
    val postActivity: PhysiologicalSignalState,
    val sleepRecovery: PhysiologicalSignalState,
    val stress: PhysiologicalSignalState,
    val hormonalResistance: PhysiologicalSignalState,
    val insulinEffectiveness: PhysiologicalSignalState,
    val sensorTrust: PhysiologicalSignalState,
    val hypoRisk: PhysiologicalSignalState,
    val hyperRisk: PhysiologicalSignalState,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("digestion", digestion.toJsonObject())
            put("meal", meal.toJsonObject())
            put("activity", activity.toJsonObject())
            put("post_activity", postActivity.toJsonObject())
            put("sleep_recovery", sleepRecovery.toJsonObject())
            put("stress", stress.toJsonObject())
            put("hormonal_resistance", hormonalResistance.toJsonObject())
            put("insulin_effectiveness", insulinEffectiveness.toJsonObject())
            put("sensor_trust", sensorTrust.toJsonObject())
            put("hypo_risk", hypoRisk.toJsonObject())
            put("hyper_risk", hyperRisk.toJsonObject())
        }
}

data class PhysiologicalLeaves(
    val userSummary: String,
    val auditorNotes: List<String>,
    val advisorHints: List<String>,
    val mealAdvisorHints: List<String>,
    val aimiContextNotes: List<String>,
    val safetyNotes: List<String>,
    val decisionExplanation: List<String>,
    val noActionReasons: List<String>,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("user_summary", userSummary)
            put("auditor_notes", JsonArray(auditorNotes.map { JsonPrimitive(it) }))
            put("advisor_hints", JsonArray(advisorHints.map { JsonPrimitive(it) }))
            put("meal_advisor_hints", JsonArray(mealAdvisorHints.map { JsonPrimitive(it) }))
            put("aimi_context_notes", JsonArray(aimiContextNotes.map { JsonPrimitive(it) }))
            put("safety_notes", JsonArray(safetyNotes.map { JsonPrimitive(it) }))
            put("decision_explanation", JsonArray(decisionExplanation.map { JsonPrimitive(it) }))
            put("no_action_reasons", JsonArray(noActionReasons.map { JsonPrimitive(it) }))
        }
}

data class PhysiologicalFruits(
    val observedOutcome: String?,
    val learningSignals: List<String>,
    val mlTrainingSignals: List<String>,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("observed_outcome", observedOutcome)
            put("learning_signals", JsonArray(learningSignals.map { JsonPrimitive(it) }))
            put("ml_training_signals", JsonArray(mlTrainingSignals.map { JsonPrimitive(it) }))
        }
}

data class PhysiologicalSeasons(
    val circadianPattern: String?,
    val weeklyPattern: String?,
    val hormonalPattern: String?,
    val recurringResistancePattern: String?,
    val recurringSensitivityPattern: String?,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("circadian_pattern", circadianPattern)
            put("weekly_pattern", weeklyPattern)
            put("hormonal_pattern", hormonalPattern)
            put("recurring_resistance_pattern", recurringResistancePattern)
            put("recurring_sensitivity_pattern", recurringSensitivityPattern)
        }
}

data class PhysiologicalSignalState(
    val detected: Boolean,
    val confidence: Double,
    val intensity: Double,
    val label: String,
    val reasons: List<String>,
    val safetyImpact: String? = null,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("detected", detected)
            put("confidence", confidence.coerceIn(0.0, 1.0))
            put("intensity", intensity.coerceIn(0.0, 1.0))
            put("label", label)
            put("reasons", JsonArray(reasons.map { JsonPrimitive(it) }))
            put("safety_impact", safetyImpact)
        }
}

enum class GlobalPhysiologicalState {
    STABLE,
    DIGESTION_ACTIVE,
    MEAL_PROBABLE,
    RESISTANCE_PROBABLE,
    SENSITIVITY_INCREASED,
    POST_ACTIVITY,
    SLEEP_RECOVERY,
    STRESS_PROBABLE,
    SENSOR_UNCERTAIN,
    HYPO_RISK,
    HYPER_RISK,
    MIXED,
    UNKNOWN,
}

enum class PhysiologicalRiskLevel {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL,
}

enum class DataCoherenceLevel {
    GOOD,
    PARTIAL,
    LOW,
    CONFLICTING,
    UNKNOWN,
}

internal object PhysiologicalTreeBuilder {

    /**
     * @param enabled Production dose path always passes `true` (cascade native). `false` is retained
     * only for unit tests / explicit null escape — never wire to `AimiPhysioAssistantEnable`.
     */
    fun build(
        enabled: Boolean,
        patientState: PatientStateSnapshot,
        patientModeDecision: PatientModeOrchestrator.Decision,
        physioLive: PhysioLiveDigest = PhysioLiveDigest(),
        thermalBelief: ThermalBeliefDigest = ThermalBeliefDigest.EMPTY,
        timestampMs: Long = patientState.timestampMs,
        currentBgMgdl: Double? = null,
        deltaMgdl5m: Double? = null,
        // Sensor effort belief confidences (0..1) injected from EffortActivityBelief so the activity /
        // post-activity branches reflect real multi-window effort + its ~120-min memory, not just the coarse
        // 15-min step count. Harmonia then reads these branches to choose PROTECTIVE_REDUCTION natively.
        effortActiveConfidence: Double = 0.0,
        effortRecentConfidence: Double = 0.0,
        /** Lot A endocrine belief — context only; does not grant dose authority. */
        wCycleBelief: WCycleBelief? = null,
        /** Wave3 F4 — body kinetics evidence for leaves (not a dosing command). */
        bodyKinetics: BodyKineticsDigest = BodyKineticsDigest.EMPTY,
    ): PhysiologicalTreeSnapshot? {
        if (!enabled) return null

        val branches = buildBranches(
            patientState, patientModeDecision, physioLive, thermalBelief,
            effortActiveConfidence, effortRecentConfidence, wCycleBelief,
        )
        val trunk = buildTrunk(patientState, patientModeDecision, branches, currentBgMgdl, deltaMgdl5m)
        val roots = buildRoots(patientState)
        val leaves = buildLeaves(patientState, patientModeDecision, branches, trunk, physioLive, bodyKinetics)
        val fruits = buildFruits(patientState, trunk)
        val seasons = buildSeasons(patientState, thermalBelief, wCycleBelief)
        val compactSummary = buildCompactSummary(trunk, branches)
        val insulin = resolveInsulinIntent(
            trunk = trunk,
            branches = branches,
            deltaMgdl5m = deltaMgdl5m,
            currentBgMgdl = currentBgMgdl,
        )

        return PhysiologicalTreeSnapshot(
            timestamp = timestampMs,
            roots = roots,
            trunk = trunk,
            branches = branches,
            leaves = leaves.copy(userSummary = compactSummary),
            fruits = fruits,
            seasons = seasons,
            physioLive = physioLive,
            compactSummary = compactSummary,
            insulinIntent = insulin.intent,
            insulinUrgency = insulin.urgency,
        )
    }

    private data class InsulinIntentResolved(
        val intent: InsulinIntent,
        val urgency: Double,
    )

    /**
     * Re-resolves the insulin intent once [MealCertainty] is known for the tick.
     *
     * The tree is built before `MealCertainty`, because `MealCertainty` reads the trunk state and the
     * meal/digestion branches. So the first pass cannot see whether the meal is confirmed, and an
     * activity signal wins by default. This second pass changes nothing except the intent, and only
     * when the meal reached `HIGH` — measured on the 2026-08-10 lunch, where the activity branch held
     * the intent at `PROTECTIVE` on every tick from BG 144 to BG 269 while `post_activity` came from
     * `effort_recent` alone (0.79 at BG 237, with the biometric afterburn probability at 0.20).
     *
     * @param mealOverridesProtective `MealCertainty.supportsMealOverProtective` — level `HIGH`, which
     *   already requires DIGESTION_ACTIVE, an OK rise, BG above the meal band, and terminals that do
     *   not signal a hypo conflict.
     */
    fun withMealCertainty(
        snapshot: PhysiologicalTreeSnapshot,
        mealOverridesProtective: Boolean,
        deltaMgdl5m: Double?,
        currentBgMgdl: Double?,
    ): PhysiologicalTreeSnapshot {
        if (!mealOverridesProtective) return snapshot
        val revised = resolveInsulinIntent(
            trunk = snapshot.trunk,
            branches = snapshot.branches,
            deltaMgdl5m = deltaMgdl5m,
            currentBgMgdl = currentBgMgdl,
            mealOverridesProtective = true,
        )
        if (revised.intent == snapshot.insulinIntent) return snapshot
        return snapshot.copy(insulinIntent = revised.intent, insulinUrgency = revised.urgency)
    }

    /**
     * Deploys a clear insulin intent when digestion/meal + rise geometry say more insulin is needed.
     * Dose amplitude remains Harmonia's job (up to maxSMBHB / hard envelope).
     *
     * @param mealOverridesProtective when true, a confirmed meal outranks the activity / post-activity
     *   veto. The hypo gate below is never overridden — a hypo risk still returns `PROTECTIVE` first.
     */
    private fun resolveInsulinIntent(
        trunk: PhysiologicalTrunk,
        branches: PhysiologicalBranches,
        deltaMgdl5m: Double?,
        currentBgMgdl: Double?,
        mealOverridesProtective: Boolean = false,
    ): InsulinIntentResolved {
        if (trunk.globalState == GlobalPhysiologicalState.HYPO_RISK ||
            trunk.globalState == GlobalPhysiologicalState.SLEEP_RECOVERY ||
            branches.hypoRisk.confidence >= 0.55
        ) {
            return InsulinIntentResolved(InsulinIntent.PROTECTIVE, branches.hypoRisk.confidence)
        }
        if (!mealOverridesProtective &&
            (branches.activity.confidence >= 0.60 || branches.postActivity.confidence >= 0.55)
        ) {
            return InsulinIntentResolved(InsulinIntent.PROTECTIVE, max(branches.activity.confidence, branches.postActivity.confidence))
        }
        val delta = deltaMgdl5m ?: 0.0
        val bg = currentBgMgdl ?: 0.0
        val mealLike =
            trunk.globalState == GlobalPhysiologicalState.DIGESTION_ACTIVE ||
                trunk.globalState == GlobalPhysiologicalState.MEAL_PROBABLE ||
                branches.digestion.detected ||
                branches.meal.confidence >= 0.55
        val riseConfirmed = delta >= 1.2 && bg >= 140.0
        if (mealLike && riseConfirmed) {
            val urgency = max(
                branches.meal.confidence,
                max(branches.digestion.confidence, branches.hyperRisk.confidence),
            ).coerceIn(0.0, 1.0)
            return InsulinIntentResolved(InsulinIntent.NEED_MORE_INSULIN, urgency)
        }
        if (mealLike && delta >= 0.8) {
            return InsulinIntentResolved(
                InsulinIntent.MEAL_SUPPORT,
                max(branches.meal.confidence, branches.digestion.confidence).coerceIn(0.0, 1.0),
            )
        }
        if (trunk.globalState == GlobalPhysiologicalState.STABLE ||
            trunk.globalState == GlobalPhysiologicalState.UNKNOWN
        ) {
            return InsulinIntentResolved(InsulinIntent.STABILIZE, 0.35)
        }
        return InsulinIntentResolved(InsulinIntent.NONE, 0.0)
    }

    private fun buildRoots(state: PatientStateSnapshot): PhysiologicalRoots =
        PhysiologicalRoots(
            profileState = signal(true, 1.0, 1.0, "Loop profile root", "baseline profile remains the dosing authority"),
            tddState = signal(
                detected = state.eventMemory != PatientEventMemory.EMPTY,
                confidence = max(state.eventMemory.recentHyperLoad, state.eventMemory.recentHypoLoad),
                intensity = max(state.eventMemory.recentHyperLoad, state.eventMemory.recentHypoLoad),
                label = "Recent load memory",
                reasons = listOf("hyper=${fmt(state.eventMemory.recentHyperLoad)} hypo=${fmt(state.eventMemory.recentHypoLoad)}"),
            ),
            // NOTE: the tree is NOT observational-only. Harmonia is built from these branches
            // (HarmoniaDecisionEngine.evaluate(tree=…)) and DOES reach the pump via planHarmoniaProductionBranch →
            // setTempBasal. The old "Lot 1 read-only / no write path" labels were stale and misled a reader into
            // thinking physiology can't decide — corrected below.
            basalState = signal(true, 1.0, 0.0, "Basal root", "basal is written by Harmonia production + the AIMI cascade"),
            isfState = signal(true, 1.0, 0.0, "ISF root", "ISF fused via dynISF/pkpd; not written by the Harmonia decision"),
            preferenceState = signal(
                true,
                1.0,
                0.0,
                "Existing preference surface",
                "AimiPhysioAssistantEnable gates vitals multipliers/extras only; tree deploy is always-on",
            ),
            historicalPatternState = signal(
                detected = state.causalPosterior.dominant != CausalStateId.UNKNOWN,
                confidence = state.causalPosterior.dominantConfidence,
                intensity = state.causalPosterior.dominantConfidence,
                label = state.causalPosterior.dominant.name,
                reasons = listOf("causal posterior source=${state.causalPosterior.source}"),
            ),
            mlAsyncState = signal(
                detected = false,
                confidence = 1.0,
                intensity = 0.0,
                label = "Async ML only",
                // Scope: THIS root only — the tree adds no *synchronous* ML call; ML (SMB TFLite, basal NN) runs
                // async and feeds dosing through its own paths. Not a statement about the tree's authority
                // (the tree drives Harmonia which reaches the pump — see basalState note above).
                reasons = listOf("no synchronous ML call inside the tree; ML runs async"),
                safetyImpact = "this root carries no direct dosing command",
            ),
        )

    private fun buildBranches(
        state: PatientStateSnapshot,
        mode: PatientModeOrchestrator.Decision,
        physioLive: PhysioLiveDigest,
        thermal: ThermalBeliefDigest,
        effortActiveConfidence: Double = 0.0,
        effortRecentConfidence: Double = 0.0,
        wCycleBelief: WCycleBelief? = null,
    ): PhysiologicalBranches {
        val causal = state.causalPosterior
        val digestionActive = state.mealAbsorptionPhase.isActive || state.mealAbsorptionBelief >= 0.20
        val mealConfidence = max(state.mealProb, causal.mealConfidence)
        val activityConfidence = max(
            if (state.userIntent.hasActivity) state.userIntent.avgConfidence else 0.0,
            // Efficient step thresholds (≈40 / 25 steps/min) so sustained movement registers; the injected
            // effort belief (5-min window + memory) is fused above via effortActiveConfidence for bursts.
            when {
                physioLive.stepsLast15m >= 600 -> 0.80
                physioLive.stepsLast15m >= 375 -> 0.58
                physioLive.hrNowBpm > 0 && physioLive.rhrRestingBpm > 0 &&
                    physioLive.hrNowBpm >= physioLive.rhrRestingBpm + 30 -> 0.62
                else -> 0.0
            },
        )
        val stressConfidence = maxOf(
            state.transientResistanceProb,
            causal.stressResistanceProb,
            if (state.userIntent.hasStress) state.userIntent.avgConfidence else 0.0,
            thermal.inflammationIndex * 0.60,
        )
        val wCycleHormonalConfidence = wCyclePhaseConfidence(wCycleBelief)
        val hormonalConfidence = maxOf(
            state.endogenousGlucoseDrive,
            causal.dawnEndogenousProb,
            state.thermalInflammationIndex * 0.42,
            if (thermal.hypothesis == ThermalHypothesis.CYCLE_BBT_RISE) 0.46 else 0.0,
            wCycleHormonalConfidence,
        )
        val hypoConfidence = maxOf(
            state.postHypoReboundProb,
            causal.postHypoRecoveryProb,
            state.eventMemory.recentHypoLoad,
            state.eventMemory.correctionFragilityScore,
        )
        val hyperConfidence = maxOf(
            state.eventMemory.recentHyperLoad,
            mealConfidence * 0.72,
            stressConfidence * 0.68,
            hormonalConfidence * 0.62,
        )
        val sensorConfidence = state.sensorConfidence.coerceIn(0.0, 1.0)
        val reducedEffectiveness = maxOf(
            state.transientResistanceProb,
            state.eventMemory.postHyperExhaustionScore,
            state.eventMemory.correctionFragilityScore * 0.82,
        )

        return PhysiologicalBranches(
            digestion = signal(
                detected = digestionActive,
                confidence = state.mealAbsorptionBelief,
                intensity = state.mealAbsorptionBelief,
                label = state.mealAbsorptionPhase.name,
                reasons = listOf("absorption=${state.mealAbsorptionPhase.name}", "belief=${fmt(state.mealAbsorptionBelief)}"),
                safetyImpact = if (state.mealAbsorptionPhase == MealAbsorptionPhase.LATE_FAT) "late absorption needs patience" else null,
            ),
            meal = signal(
                detected = mealConfidence >= 0.35,
                confidence = mealConfidence,
                intensity = mealConfidence,
                label = if (mode.strategyHint == PatientStrategyHint.SMB_PRIORITY) "Meal support likely" else "Meal evidence",
                reasons = listOf("meal_prob=${fmt(state.mealProb)}", "causal_meal=${fmt(causal.mealConfidence)}"),
                safetyImpact = if (state.falseMealSuppression) "meal interpretation suppressed by protective context" else null,
            ),
            activity = signal(
                // Fuse the coarse step count with the sensor effort belief (multi-window, HR) so a burst
                // pattern (high 5-min rate, modest 15-min count) is seen — Harmonia reads this to protect.
                detected = maxOf(activityConfidence, effortActiveConfidence) >= 0.30,
                confidence = maxOf(activityConfidence, effortActiveConfidence),
                intensity = maxOf(activityConfidence, effortActiveConfidence),
                label = physioLive.activityState.ifBlank { "Activity" },
                reasons = listOf("steps15=${physioLive.stepsLast15m}", "hr=${physioLive.hrNowBpm}", "effort=${fmt(effortActiveConfidence)}"),
                safetyImpact = if (maxOf(activityConfidence, effortActiveConfidence) >= 0.55) "watch for increased insulin sensitivity" else null,
            ),
            postActivity = signal(
                // Post-effort adrenaline / sensitivity window: the effort belief's ~120-min memory drives this
                // even when the biometric afterburn probability has not fired.
                detected = maxOf(causal.exerciseAfterburnProb, effortRecentConfidence) >= 0.25,
                confidence = maxOf(causal.exerciseAfterburnProb, effortRecentConfidence),
                intensity = maxOf(causal.exerciseAfterburnProb, effortRecentConfidence),
                label = "Post-activity sensitivity",
                reasons = listOf("exercise_afterburn=${fmt(causal.exerciseAfterburnProb)}", "effort_recent=${fmt(effortRecentConfidence)}"),
                safetyImpact = if (maxOf(causal.exerciseAfterburnProb, effortRecentConfidence) >= 0.45) "hypo-prone recovery context" else null,
            ),
            sleepRecovery = signal(
                detected = state.sleepDebtScore >= 0.25 || thermal.recoveryBurden >= 0.25,
                confidence = max(state.sleepDebtScore, thermal.recoveryBurden),
                intensity = max(state.sleepDebtScore, thermal.recoveryBurden),
                label = "Sleep/recovery burden",
                reasons = listOf("sleep=${fmt(state.sleepDebtScore)}", "thermal_recovery=${fmt(thermal.recoveryBurden)}"),
                safetyImpact = if (thermal.recoveryBurden >= 0.45) "recovery may increase variability" else null,
            ),
            stress = signal(
                detected = stressConfidence >= 0.30,
                confidence = stressConfidence,
                intensity = stressConfidence,
                label = "Stress/resistance",
                reasons = listOf("resistance=${fmt(state.transientResistanceProb)}", "causal_stress=${fmt(causal.stressResistanceProb)}"),
                safetyImpact = if (stressConfidence >= 0.55) "avoid false meal shortcut" else null,
            ),
            hormonalResistance = signal(
                detected = hormonalConfidence >= 0.30,
                confidence = hormonalConfidence,
                intensity = hormonalConfidence,
                label = hormonalLabel(wCycleBelief),
                reasons = hormonalReasons(state, causal, wCycleBelief),
                safetyImpact = hormonalSafetyImpact(hormonalConfidence, wCycleBelief, hypoConfidence),
            ),
            insulinEffectiveness = signal(
                detected = reducedEffectiveness >= 0.30,
                confidence = reducedEffectiveness,
                intensity = reducedEffectiveness,
                label = if (reducedEffectiveness >= 0.30) "Reduced effectiveness possible" else "Effectiveness nominal",
                reasons = listOf("exhaustion=${fmt(state.eventMemory.postHyperExhaustionScore)}", "fragility=${fmt(state.eventMemory.correctionFragilityScore)}"),
                safetyImpact = if (reducedEffectiveness >= 0.55) "do not teach ML from dirty context" else null,
            ),
            sensorTrust = signal(
                detected = sensorConfidence >= 0.55,
                confidence = sensorConfidence,
                intensity = sensorConfidence,
                label = if (sensorConfidence >= 0.55) "Sensor ok" else "Sensor uncertain",
                reasons = listOf("sensor_confidence=${fmt(sensorConfidence)}"),
                safetyImpact = if (sensorConfidence < 0.45) "degraded data coherence" else null,
            ),
            hypoRisk = signal(
                detected = hypoConfidence >= 0.25,
                confidence = hypoConfidence,
                intensity = hypoConfidence,
                label = "Hypo/recovery risk",
                reasons = listOf("post_hypo=${fmt(state.postHypoReboundProb)}", "recent_hypo=${fmt(state.eventMemory.recentHypoLoad)}"),
                safetyImpact = if (hypoConfidence >= 0.45) "safety gates remain dominant" else null,
            ),
            hyperRisk = signal(
                detected = hyperConfidence >= 0.35,
                confidence = hyperConfidence,
                intensity = hyperConfidence,
                label = "Hyper load",
                reasons = listOf("recent_hyper=${fmt(state.eventMemory.recentHyperLoad)}", "drivers meal/stress/hormonal"),
                safetyImpact = if (hyperConfidence >= 0.60) "context explains persistence, not a dosing command" else null,
            ),
        )
    }

    private fun buildTrunk(
        state: PatientStateSnapshot,
        mode: PatientModeOrchestrator.Decision,
        branches: PhysiologicalBranches,
        currentBgMgdl: Double?,
        deltaMgdl5m: Double?,
    ): PhysiologicalTrunk {
        val effectiveHypoConfidence = effectiveHypoConfidenceForTrunk(
            state = state,
            branches = branches,
            currentBgMgdl = currentBgMgdl,
            deltaMgdl5m = deltaMgdl5m,
        )
        val dominantSignals = listOf(
            branches.digestion,
            branches.meal,
            branches.activity,
            branches.postActivity,
            branches.sleepRecovery,
            branches.stress,
            branches.hormonalResistance,
            branches.insulinEffectiveness,
            branches.hypoRisk,
            branches.hyperRisk,
        ).filter { it.detected && it.confidence >= 0.30 }

        val coherence = dataCoherence(state)
        val global = when {
            branches.sensorTrust.confidence < 0.40 -> GlobalPhysiologicalState.SENSOR_UNCERTAIN
            effectiveHypoConfidence >= 0.55 -> GlobalPhysiologicalState.HYPO_RISK
            isConflicting(branches) -> GlobalPhysiologicalState.MIXED
            branches.digestion.detected && branches.meal.confidence >= 0.55 -> GlobalPhysiologicalState.DIGESTION_ACTIVE
            branches.meal.confidence >= 0.55 -> GlobalPhysiologicalState.MEAL_PROBABLE
            branches.hormonalResistance.confidence >= 0.55 -> GlobalPhysiologicalState.RESISTANCE_PROBABLE
            branches.stress.confidence >= 0.55 -> GlobalPhysiologicalState.STRESS_PROBABLE
            branches.postActivity.confidence >= 0.45 -> GlobalPhysiologicalState.POST_ACTIVITY
            branches.sleepRecovery.confidence >= 0.45 -> GlobalPhysiologicalState.SLEEP_RECOVERY
            branches.hyperRisk.confidence >= 0.65 -> GlobalPhysiologicalState.HYPER_RISK
            dominantSignals.isEmpty() && coherence == DataCoherenceLevel.UNKNOWN -> GlobalPhysiologicalState.UNKNOWN
            dominantSignals.isEmpty() -> GlobalPhysiologicalState.STABLE
            else -> GlobalPhysiologicalState.MIXED
        }
        val confidence = when (global) {
            GlobalPhysiologicalState.SENSOR_UNCERTAIN -> 1.0 - branches.sensorTrust.confidence
            GlobalPhysiologicalState.MIXED -> dominantSignals.maxOfOrNull { it.confidence } ?: mode.confidence
            GlobalPhysiologicalState.STABLE -> max(mode.confidence, state.sensorConfidence).coerceIn(0.0, 1.0)
            GlobalPhysiologicalState.UNKNOWN -> 0.0
            else -> dominantSignals.maxOfOrNull { it.confidence } ?: mode.confidence
        }.coerceIn(0.0, 1.0)

        return PhysiologicalTrunk(
            globalState = global,
            confidence = confidence,
            riskLevel = riskLevel(
                hypoConfidence = effectiveHypoConfidence,
                branches = branches,
                coherence = coherence,
            ),
            dataCoherence = coherence,
            mainReason = mainReason(global, branches, mode),
        )
    }

    /**
     * During active hyper correction, stale event-memory hypo load must not dominate the trunk.
     */
    private fun effectiveHypoConfidenceForTrunk(
        state: PatientStateSnapshot,
        branches: PhysiologicalBranches,
        currentBgMgdl: Double?,
        deltaMgdl5m: Double?,
    ): Double {
        val branchHypo = branches.hypoRisk.confidence
        if (currentBgMgdl == null || deltaMgdl5m == null) return branchHypo
        val liveHypo = maxOf(
            state.postHypoReboundProb,
            state.causalPosterior.postHypoRecoveryProb,
        )
        val memoryHypo = maxOf(
            state.eventMemory.recentHypoLoad,
            state.eventMemory.correctionFragilityScore,
        )
        val suppressStaleMemoryHypo =
            currentBgMgdl > 180.0 &&
            deltaMgdl5m > 0.0 &&
            liveHypo < 0.55 &&
            branchHypo >= 0.55 &&
            memoryHypo >= 0.55
        return if (suppressStaleMemoryHypo) liveHypo.coerceIn(0.0, 1.0) else branchHypo
    }

    private fun buildLeaves(
        state: PatientStateSnapshot,
        mode: PatientModeOrchestrator.Decision,
        branches: PhysiologicalBranches,
        trunk: PhysiologicalTrunk,
        physioLive: PhysioLiveDigest,
        bodyKinetics: BodyKineticsDigest = BodyKineticsDigest.EMPTY,
    ): PhysiologicalLeaves {
        val safetyNotes = buildList {
            if (branches.hypoRisk.confidence >= 0.45) add("Hypo/post-hypo context: hard safety remains dominant")
            if (branches.sensorTrust.confidence < 0.45) add("Sensor uncertainty: prefer degraded interpretation over guessing")
            if (state.falseMealSuppression) add("False-meal suppression is active")
            if (bodyKinetics.peakHeavy) add("Insulin kinetics PEAK: avoid stacking meal escalation")
            if (bodyKinetics.tailHeavy) add("Insulin kinetics TAIL: late active insulin — stacking prudence")
        }
        val advisorHints = buildList {
            if (trunk.globalState == GlobalPhysiologicalState.MIXED) add("Review family balance before changing individual preferences")
            if (branches.insulinEffectiveness.confidence >= 0.45) add("Stabilize resistance/recovery context before tuning aggressiveness")
            if (branches.sensorTrust.confidence < 0.45) add("Improve sensor confidence before interpreting outcomes")
        }
        val mealAdvisorHints = buildList {
            if (branches.meal.confidence >= 0.55) add("Meal evidence is present; check declared vs undeclared meal context")
            if (branches.hormonalResistance.confidence >= branches.meal.confidence + 0.10) add("Endogenous/hormonal driver competes with meal interpretation")
            if (branches.digestion.label == MealAbsorptionPhase.LATE_FAT.name) add("Late fat/protein absorption may explain delayed rise")
        }
        val auditorNotes = buildList {
            add("Tree deploys insulin intent; Harmonia arbitrates basal+SMB within hard envelope")
            add("Auditor may CONFIRM or SOFTEN only — never lift SMB")
            if (branches.insulinEffectiveness.confidence >= 0.55) add("Dirty physiology context may explain delayed correction")
            if (bodyKinetics.reason != "empty") add("Body kinetics: ${bodyKinetics.reason}")
        }
        val contextNotes = buildList {
            add("Patient mode=${mode.mode.name} strategy=${mode.strategyHint.name}")
            if (physioLive.stepsLast15m > 0 || physioLive.hrNowBpm > 0) {
                add("Live body: steps15=${physioLive.stepsLast15m} hr=${physioLive.hrNowBpm}")
            }
            if (bodyKinetics.reason != "empty") {
                add("Kinetics: ${bodyKinetics.reason}")
            }
        }
        return PhysiologicalLeaves(
            userSummary = "",
            auditorNotes = auditorNotes,
            advisorHints = advisorHints,
            mealAdvisorHints = mealAdvisorHints,
            aimiContextNotes = contextNotes,
            safetyNotes = safetyNotes,
            decisionExplanation = listOf(
                "mode=${mode.mode.name}",
                "strategy=${mode.strategyHint.name}",
                "reasons=${mode.reasonCodes.joinToString(",")}",
            ),
            noActionReasons = buildList {
                if (trunk.globalState == GlobalPhysiologicalState.SENSOR_UNCERTAIN) add("sensor_uncertain")
                if (branches.hypoRisk.confidence >= 0.55) add("hypo_or_recovery_risk")
                if (trunk.globalState == GlobalPhysiologicalState.STABLE) add("stabilize_no_escalation")
            },
        )
    }

    private fun buildFruits(state: PatientStateSnapshot, trunk: PhysiologicalTrunk): PhysiologicalFruits =
        PhysiologicalFruits(
            observedOutcome = null,
            learningSignals = listOf(
                "dominant=${trunk.globalState.name}",
                "causal_learning_quality=${fmt(state.causalPosterior.learningQuality)}",
                "data_coherence=${trunk.dataCoherence.name}",
            ),
            mlTrainingSignals = buildList {
                add("ml_async_only")
                if (state.causalPosterior.learningContextClean()) {
                    add("causal_context_clean")
                } else {
                    add("causal_context_dirty")
                }
            },
        )

    private fun buildSeasons(
        state: PatientStateSnapshot,
        thermal: ThermalBeliefDigest,
        wCycleBelief: WCycleBelief? = null,
    ): PhysiologicalSeasons =
        PhysiologicalSeasons(
            circadianPattern = when {
                state.causalPosterior.dawnEndogenousProb >= 0.45 -> "dawn_or_endogenous"
                state.phase.isHormonalRisk -> "hormonal_phase"
                wCycleBelief?.enabled == true && wCycleBelief.dawnBias > 1.0 -> "wcycle_luteal_dawn"
                else -> null
            },
            weeklyPattern = null,
            hormonalPattern = when {
                thermal.hypothesis == ThermalHypothesis.CYCLE_BBT_RISE -> "cycle_bbt_rise"
                wCycleBelief?.enabled == true && wCycleBelief.phase != CyclePhase.UNKNOWN ->
                    "wcycle_${wCycleBelief.phase.name.lowercase()}_j${wCycleBelief.dayInCycle}"
                else -> null
            },
            recurringResistancePattern = if (state.eventMemory.postHyperExhaustionScore >= 0.45) {
                "post_hyper_exhaustion"
            } else {
                null
            },
            recurringSensitivityPattern = if (state.causalPosterior.exerciseAfterburnProb >= 0.35) {
                "post_activity_sensitivity"
            } else {
                null
            },
        )

    private fun wCyclePhaseConfidence(belief: WCycleBelief?): Double {
        if (belief == null || !belief.enabled) return 0.0
        val phaseFloor = when (belief.phase) {
            CyclePhase.LUTEAL -> 0.58
            CyclePhase.OVULATION -> 0.42
            CyclePhase.MENSTRUATION -> 0.32
            CyclePhase.FOLLICULAR -> 0.22
            CyclePhase.UNKNOWN -> 0.0
        }
        // Governor-effective amp (hypo-dampened) drives intensity — not the legacy raw uplift alone.
        val ampLift = ((belief.effectiveBasalAmp - 1.0).coerceAtLeast(0.0) / 0.30).coerceIn(0.0, 1.0)
        return max(phaseFloor, belief.confidence * 0.85 + ampLift * 0.15)
    }

    private fun hormonalLabel(belief: WCycleBelief?): String {
        if (belief == null || !belief.enabled || belief.phase == CyclePhase.UNKNOWN) {
            return "Endogenous/hormonal drive"
        }
        return "WCycle ${belief.phase.name} J${belief.dayInCycle}"
    }

    private fun hormonalReasons(
        state: PatientStateSnapshot,
        causal: CausalStatePosterior,
        belief: WCycleBelief?,
    ): List<String> {
        val base = listOf(
            "endogenous=${fmt(state.endogenousGlucoseDrive)}",
            "causal_dawn=${fmt(causal.dawnEndogenousProb)}",
        )
        if (belief == null || !belief.enabled) return base
        return base + listOf(
            "wcycle_phase=${belief.phase.name}",
            "wcycle_day=${belief.dayInCycle}",
            "effective_basal=${fmt(belief.effectiveBasalAmp)}",
            "legacy_basal=${fmt(belief.legacyDoseBasalAmp)}",
            "hypo_dampen=${fmt(belief.hypoLoadDampen)}",
            "verneuil=${belief.verneuil.name}",
        )
    }

    private fun hormonalSafetyImpact(
        hormonalConfidence: Double,
        belief: WCycleBelief?,
        hypoConfidence: Double,
    ): String? {
        if (belief?.hypoGuardActive == true && belief.effectiveBasalAmp > 1.02 && hypoConfidence >= 0.35) {
            return "luteal uplift dampened under HYPO_GUARD — prefer protect over basal bridge"
        }
        if (hypoConfidence >= 0.45 && hormonalConfidence >= 0.45) {
            return "hormonal resistance with hypo risk — protective posture preferred"
        }
        if (hormonalConfidence >= 0.55) {
            return "prefer causal discrimination before meal escalation"
        }
        return null
    }

    private fun buildCompactSummary(
        trunk: PhysiologicalTrunk,
        branches: PhysiologicalBranches,
    ): String {
        val stateLabel = when (trunk.globalState) {
            GlobalPhysiologicalState.DIGESTION_ACTIVE -> "digestion + meal probable"
            GlobalPhysiologicalState.MEAL_PROBABLE -> "meal probable"
            GlobalPhysiologicalState.RESISTANCE_PROBABLE -> "resistance probable"
            GlobalPhysiologicalState.STRESS_PROBABLE -> "stress probable"
            GlobalPhysiologicalState.SENSOR_UNCERTAIN -> "sensor uncertain"
            GlobalPhysiologicalState.HYPO_RISK -> "hypo/recovery risk"
            GlobalPhysiologicalState.HYPER_RISK -> "hyper load"
            GlobalPhysiologicalState.MIXED -> "mixed physiology"
            GlobalPhysiologicalState.POST_ACTIVITY -> "post-activity"
            GlobalPhysiologicalState.SLEEP_RECOVERY -> "sleep/recovery"
            GlobalPhysiologicalState.SENSITIVITY_INCREASED -> "sensitivity increased"
            GlobalPhysiologicalState.STABLE -> "stable baseline"
            GlobalPhysiologicalState.UNKNOWN -> "unknown"
        }
        val sensorLabel = if (branches.sensorTrust.confidence >= 0.55) "sensor ok" else "sensor uncertain"
        return "Tree: $stateLabel | conf ${pct(trunk.confidence)} | risk ${trunk.riskLevel.name.lowercase()} | $sensorLabel"
    }

    private fun dataCoherence(state: PatientStateSnapshot): DataCoherenceLevel {
        val sensor = state.sensorConfidence
        val meal = state.causalPosterior.mealConfidence
        val protective = state.causalPosterior.protectiveConfidence
        val ambiguity = 1.0 - abs(meal - protective).coerceIn(0.0, 1.0)
        return when {
            sensor <= 0.0 && state.causalPosterior.dominant == CausalStateId.UNKNOWN -> DataCoherenceLevel.UNKNOWN
            sensor < 0.35 -> DataCoherenceLevel.LOW
            ambiguity >= 0.86 && max(meal, protective) >= 0.45 -> DataCoherenceLevel.CONFLICTING
            sensor < 0.62 || state.causalPosterior.learningQuality < 0.55 -> DataCoherenceLevel.PARTIAL
            else -> DataCoherenceLevel.GOOD
        }
    }

    private fun riskLevel(
        hypoConfidence: Double,
        branches: PhysiologicalBranches,
        coherence: DataCoherenceLevel,
    ): PhysiologicalRiskLevel {
        val risk = maxOf(
            hypoConfidence,
            branches.hyperRisk.confidence * 0.82,
            (1.0 - branches.sensorTrust.confidence).coerceIn(0.0, 1.0) * 0.90,
            branches.insulinEffectiveness.confidence * 0.60,
        )
        return when {
            hypoConfidence >= 0.75 || coherence == DataCoherenceLevel.LOW && branches.sensorTrust.confidence < 0.25 ->
                PhysiologicalRiskLevel.CRITICAL
            risk >= 0.62 -> PhysiologicalRiskLevel.HIGH
            risk >= 0.34 || coherence == DataCoherenceLevel.CONFLICTING -> PhysiologicalRiskLevel.MODERATE
            else -> PhysiologicalRiskLevel.LOW
        }
    }

    private fun isConflicting(branches: PhysiologicalBranches): Boolean =
        branches.meal.confidence >= 0.45 &&
            maxOf(branches.hormonalResistance.confidence, branches.stress.confidence, branches.hypoRisk.confidence) >=
            branches.meal.confidence - 0.04

    private fun mainReason(
        global: GlobalPhysiologicalState,
        branches: PhysiologicalBranches,
        mode: PatientModeOrchestrator.Decision,
    ): String =
        when (global) {
            GlobalPhysiologicalState.SENSOR_UNCERTAIN -> branches.sensorTrust.reasons.joinToString("; ")
            GlobalPhysiologicalState.HYPO_RISK -> branches.hypoRisk.reasons.joinToString("; ")
            GlobalPhysiologicalState.DIGESTION_ACTIVE,
            GlobalPhysiologicalState.MEAL_PROBABLE,
            -> branches.meal.reasons.joinToString("; ")
            GlobalPhysiologicalState.RESISTANCE_PROBABLE -> branches.hormonalResistance.reasons.joinToString("; ")
            GlobalPhysiologicalState.STRESS_PROBABLE -> branches.stress.reasons.joinToString("; ")
            GlobalPhysiologicalState.MIXED -> "competing meal/protective physiology"
            else -> mode.reasonCodes.joinToString(",").ifBlank { "baseline" }
        }

    private fun signal(
        detected: Boolean,
        confidence: Double,
        intensity: Double,
        label: String,
        reason: String,
        safetyImpact: String? = null,
    ): PhysiologicalSignalState =
        signal(detected, confidence, intensity, label, listOf(reason), safetyImpact)

    private fun signal(
        detected: Boolean,
        confidence: Double,
        intensity: Double,
        label: String,
        reasons: List<String>,
        safetyImpact: String? = null,
    ): PhysiologicalSignalState =
        PhysiologicalSignalState(
            detected = detected,
            confidence = confidence.coerceIn(0.0, 1.0),
            intensity = intensity.coerceIn(0.0, 1.0),
            label = label,
            reasons = reasons,
            safetyImpact = safetyImpact,
        )

    private fun fmt(value: Double): String = aimiFmt2(value)

    private fun pct(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100.0).roundToInt()}%"
}
