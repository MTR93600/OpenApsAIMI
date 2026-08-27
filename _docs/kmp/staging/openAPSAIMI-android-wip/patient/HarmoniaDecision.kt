package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import kotlin.math.roundToInt
import kotlin.random.Random

data class HarmoniaDecisionEnvironment(
    val currentBgMgdl: Double,
    val deltaMgdl5m: Double,
    val iobU: Double,
    val cobG: Double,
    val currentBasalUph: Double,
    val maxBasalUph: Double,
    val maxSmbU: Double,
    val maxIobU: Double,
    val pumpBasalStepUph: Double = 0.05,
    val pumpSmbStepU: Double = 0.05,
    val sensorAgeMin: Int = 0,
    val sensorNoise: Double = 0.0,
    val mealRiseConfirmed: Boolean = false,
    val targetBgMgdl: Double? = null,
    val correctionFragilityScore: Double = 0.0,
    val postHyperExhaustionScore: Double = 0.0,
    val chaoticEpisodeLoad: Double = 0.0,
    val effectiveDiaHours: Double? = null,
    val effectivePeakMinutes: Double? = null,
    /** Wave3 F4 — conscious body kinetics (PEAK/TAIL), not a second dose brain. */
    val bodyKinetics: BodyKineticsDigest = BodyKineticsDigest.EMPTY,
    /**
     * Production governor basal amp when WCycle is APPLIED.
     * - `null` = WCycle not governing → BASAL_FIRST keeps posture factor 1.18 (stress/dawn).
     * - `1.0` = governor hard unity / no uplift → must NOT fall back to 1.18.
     * - `>1.0` = use governor amp (capped).
     */
    val endocrineBasalAmp: Double? = null,
    val seed: Long? = null,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("current_bg_mgdl", currentBgMgdl)
            put("delta_mgdl_5m", deltaMgdl5m)
            put("iob_u", iobU)
            put("cob_g", cobG)
            put("current_basal_uph", currentBasalUph)
            put("max_basal_uph", maxBasalUph)
            put("max_smb_u", maxSmbU)
            put("max_iob_u", maxIobU)
            put("pump_basal_step_uph", pumpBasalStepUph)
            put("pump_smb_step_u", pumpSmbStepU)
            put("sensor_age_min", sensorAgeMin)
            put("sensor_noise", sensorNoise)
            put("endocrine_basal_amp", endocrineBasalAmp)
            put("meal_rise_confirmed", mealRiseConfirmed)
            put("target_bg_mgdl", targetBgMgdl)
            put("correction_fragility_score", correctionFragilityScore)
            put("post_hyper_exhaustion_score", postHyperExhaustionScore)
            put("chaotic_episode_load", chaoticEpisodeLoad)
            effectiveDiaHours?.let { put("effective_dia_h", it) }
            effectivePeakMinutes?.let { put("effective_peak_min", it) }
            put("body_kinetics", bodyKinetics.toJsonObject())
            put("seed", seed)
        }
}

/**
 * Explicit basis for why Harmonia chose [action] on the final tree trunk (cascade R2).
 * Consumers (Auditor, JSONL, UI) must read this — not infer from narrative alone.
 */
data class HarmoniaDecisionBasis(
    val trunkState: GlobalPhysiologicalState,
    val trunkConfidence: Double,
    val trunkRisk: PhysiologicalRiskLevel,
    val primaryReason: String,
    val contributingBranches: List<HarmoniaBranchContribution>,
    val actionCoherentWithTrunk: Boolean,
    val mismatchReason: String? = null,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("trunk_state", trunkState.name)
            put("trunk_confidence", trunkConfidence)
            put("trunk_risk", trunkRisk.name)
            put("primary_reason", primaryReason)
            put(
                "contributing_branches",
                buildJsonArray {
                    contributingBranches.forEach { add(it.toJsonObject()) }
                },
            )
            put("action_coherent_with_trunk", actionCoherentWithTrunk)
            put("mismatch_reason", mismatchReason)
        }
}

data class HarmoniaBranchContribution(
    val name: String,
    val confidence: Double,
    val role: String,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("confidence", confidence)
            put("role", role)
        }
}

data class HarmoniaDecision(
    val timestampMs: Long,
    val branch: String,
    val action: HarmoniaAction,
    val eligible: Boolean,
    val targetBasalUph: Double,
    val targetSmbU: Double,
    val basalFactor: Double,
    val smbFactor: Double,
    val environment: HarmoniaDecisionEnvironment,
    val capsApplied: List<String>,
    val blockers: List<String>,
    val rationale: List<String>,
    val compactSummary: String,
    val decisionBasis: HarmoniaDecisionBasis,
    val mealCertainty: MealCertainty = MealCertainty.NONE,
    val version: Int = 2,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("timestamp", timestampMs)
            put("version", version)
            put("branch", branch)
            put("action", action.name)
            put("eligible", eligible)
            put("simulated_basal_uph", targetBasalUph)
            put("simulated_smb_u", targetSmbU)
            put("basal_factor", basalFactor)
            put("smb_factor", smbFactor)
            put("environment", environment.toJsonObject())
            put("caps_applied", JsonArray(capsApplied.map { JsonPrimitive(it) }))
            put("blockers", JsonArray(blockers.map { JsonPrimitive(it) }))
            put("rationale", JsonArray(rationale.map { JsonPrimitive(it) }))
            put("compact_summary", compactSummary)
            put("decision_basis", decisionBasis.toJsonObject())
            put("meal_certainty", mealCertainty.toJsonObject())
            put("simulation_only", true)
            put("applies_to_pump", false)
            put("source", "harmonia_simulation_branch_v2")
        }
}

data class HarmoniaProductionDecision(
    val timestampMs: Long,
    val mode: HarmoniaProductionMode,
    val selectedForProduction: Boolean,
    val requestedRateUph: Double?,
    val boundedRateUph: Double?,
    val appliedRateUph: Double?,
    val appliedDurationMin: Int?,
    val runtimeBlocker: String?,
    val safetyBlockers: List<String>,
    val sourceAction: HarmoniaAction?,
    val branch: String?,
    val reason: String,
    val version: Int = 1,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("timestamp", timestampMs)
            put("version", version)
            put("mode", mode.name)
            put("selected_for_production", selectedForProduction)
            put("requested_rate_uph", requestedRateUph)
            put("bounded_rate_uph", boundedRateUph)
            put("applied_rate_uph", appliedRateUph)
            put("applied_duration_min", appliedDurationMin)
            put("runtime_blocker", runtimeBlocker)
            put("safety_blockers", JsonArray(safetyBlockers.map { JsonPrimitive(it) }))
            put("source_action", sourceAction?.name)
            put("branch", branch)
            put("reason", reason)
            put("basal_first_only", true)
            put("adds_smb_authority", false)
            put("applies_to_pump", mode == HarmoniaProductionMode.APPLIED)
            put("source", "harmonia_production_branch_v1")
        }
}

enum class HarmoniaProductionMode {
    SKIPPED,
    BLOCKED,
    READY,
    APPLIED,
}

enum class HarmoniaAction {
    OBSERVE,
    BASAL_FIRST,
    MEAL_SUPPORT,
    PROTECTIVE_REDUCTION,
    STABILIZE,
    BLOCKED,
}

internal object HarmoniaDecisionEngine {

    /**
     * Sensor warmup window, in minutes since insertion, during which fresh CGM readings are
     * unreliable and Harmonia must stand down. Note that [HarmoniaDecisionEnvironment.sensorAgeMin]
     * carries the real sensor age (minutes since the last SENSOR_CHANGE event), so an established
     * sensor reports thousands of minutes and is *not* in warmup. A value of `0` means the insertion
     * time is unknown (no SENSOR_CHANGE recorded) and is likewise treated as established, never warmup.
     */
    private const val SENSOR_WARMUP_MAX_MIN = 120

    /**
     * H4 rising-delta floor — aligned with [declaredMealRise] (`delta >= 0.8`). Without this guard,
     * field replay showed ~48% of H4 flips on falling BG (post-peak), which must stay protective.
     */
    internal const val H4_MIN_RISING_DELTA_MGDL = 0.8

    fun evaluate(
        tree: PhysiologicalTreeSnapshot?,
        environment: HarmoniaDecisionEnvironment?,
        timestampMs: Long = tree?.timestamp ?: 0L,
        mealCertainty: MealCertainty? = null,
        absorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        effortVeto: Boolean = false,
        softCorroboration: Boolean = false,
        pkpdEventualMgdl: Double? = null,
        scenarioTerminalMgdl: Double? = null,
        scenarioPathMinMgdl: Double? = null,
        scenarioPathMinHitFloor: Boolean = false,
    ): HarmoniaDecision? {
        if (tree == null || environment == null) return null

        val certainty = mealCertainty ?: MealCertaintyBuilder.fromTreeAndEnvironment(
            tree = tree,
            env = environment,
            absorptionPhase = absorptionPhase,
            effortVeto = effortVeto,
            softCorroboration = softCorroboration,
            pkpdEventualMgdl = pkpdEventualMgdl,
            scenarioTerminalMgdl = scenarioTerminalMgdl,
            scenarioPathMinMgdl = scenarioPathMinMgdl,
            scenarioPathMinHitFloor = scenarioPathMinHitFloor,
        )
        val blockers = buildBlockers(tree, environment)
        val capsApplied = mutableListOf<String>()
        val choice = chooseActionWithReason(tree, environment, blockers, certainty)
        val action = choice.action
        val branch = tree.trunk.globalState.name

        val rawBasalFactor = when (action) {
            HarmoniaAction.BASAL_FIRST -> when (val endocrine = environment.endocrineBasalAmp) {
                // WCycle not APPLIED: keep generic resistance posture boost (stress/dawn).
                null -> 1.18
                // Governor APPLIED (including hard unity 1.0): never invent a 1.18 fallback.
                else -> endocrine.coerceIn(1.0, 1.25)
            }
            HarmoniaAction.MEAL_SUPPORT -> 1.10
            HarmoniaAction.PROTECTIVE_REDUCTION -> 0.70
            HarmoniaAction.STABILIZE -> 0.85
            HarmoniaAction.OBSERVE,
            HarmoniaAction.BLOCKED,
            -> 1.0
        }
        val rawSmbFactor = when (action) {
            HarmoniaAction.MEAL_SUPPORT -> 0.30
            HarmoniaAction.BASAL_FIRST -> 0.0
            else -> 0.0
        }

        val basalFactor = rawBasalFactor.coerceIn(0.0, 1.25)
        if (basalFactor != rawBasalFactor) capsApplied.add("basal_factor_cap")
        val smbFactor = rawSmbFactor.coerceIn(0.0, 0.35)
        if (smbFactor != rawSmbFactor) capsApplied.add("smb_factor_cap")

        val requestedBasal = environment.currentBasalUph * basalFactor
        val simulatedBasal = roundToStep(
            requestedBasal.coerceIn(0.0, environment.maxBasalUph.coerceAtLeast(0.0)),
            environment.pumpBasalStepUph,
        )
        if (simulatedBasal != requestedBasal) capsApplied.add("pump_basal_cap_or_step")

        val iobSpace = (environment.maxIobU - environment.iobU).coerceAtLeast(0.0)
        val requestedSmb = environment.maxSmbU.coerceAtLeast(0.0) * smbFactor
        val simulatedSmb = roundToStep(
            requestedSmb.coerceIn(0.0, minOf(environment.maxSmbU.coerceAtLeast(0.0), iobSpace)),
            environment.pumpSmbStepU,
        )
        if (simulatedSmb != requestedSmb) capsApplied.add("pump_smb_or_iob_cap")

        val eligible = blockers.isEmpty() && action != HarmoniaAction.BLOCKED
        val finalAction = if (eligible) action else HarmoniaAction.BLOCKED
        val safeBasal = if (eligible) simulatedBasal else environment.currentBasalUph
        val safeSmb = if (eligible) simulatedSmb else 0.0
        val decisionBasis = buildDecisionBasis(
            tree = tree,
            environment = environment,
            action = finalAction,
            primaryReason = if (eligible) choice.primaryReason else "blocked",
            blockers = blockers,
        )
        val rationale = buildRationale(tree, environment, finalAction, blockers, decisionBasis)
        val summary = buildSummary(finalAction, branch, eligible, safeBasal, safeSmb, blockers, decisionBasis)

        return HarmoniaDecision(
            timestampMs = timestampMs,
            branch = branch,
            action = finalAction,
            eligible = eligible,
            targetBasalUph = safeBasal,
            targetSmbU = safeSmb,
            basalFactor = if (eligible) basalFactor else 1.0,
            smbFactor = if (eligible) smbFactor else 0.0,
            environment = environment,
            capsApplied = capsApplied.distinct(),
            blockers = blockers,
            rationale = rationale,
            compactSummary = summary,
            decisionBasis = decisionBasis,
            mealCertainty = certainty,
        )
    }

    fun randomizedEnvironment(
        seed: Long,
        currentBasalUph: Double = 1.0,
        maxBasalUph: Double = 5.0,
        maxSmbU: Double = 1.0,
        maxIobU: Double = 5.0,
    ): HarmoniaDecisionEnvironment {
        val random = Random(seed)
        return HarmoniaDecisionEnvironment(
            currentBgMgdl = random.nextDouble(65.0, 290.0),
            deltaMgdl5m = random.nextDouble(-5.0, 8.0),
            iobU = random.nextDouble(0.0, maxIobU.coerceAtLeast(0.1)),
            cobG = random.nextDouble(0.0, 80.0),
            currentBasalUph = currentBasalUph,
            maxBasalUph = maxBasalUph,
            maxSmbU = maxSmbU,
            maxIobU = maxIobU,
            sensorAgeMin = random.nextInt(0, 15),
            sensorNoise = random.nextDouble(0.0, 1.0),
            seed = seed,
        )
    }

    private fun buildBlockers(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
    ): List<String> =
        buildList {
            if (env.sensorAgeMin in 1..SENSOR_WARMUP_MAX_MIN) add("sensor_warmup")
            if (env.sensorNoise >= 0.75) add("sensor_noise")
            if (tree.branches.sensorTrust.confidence < 0.40) add("sensor_uncertain")
            if (tree.branches.hypoRisk.confidence >= 0.45) add("hypo_or_recovery")
            if (env.currentBgMgdl < 80.0 && env.deltaMgdl5m <= 0.0) add("low_or_falling_bg")
            if (env.iobU >= env.maxIobU * 0.92) add("max_iob_pressure")
            if (tree.trunk.riskLevel == PhysiologicalRiskLevel.CRITICAL) add("critical_risk")
        }

    private data class ActionChoice(
        val action: HarmoniaAction,
        val primaryReason: String,
    )

    private fun chooseActionWithReason(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
        blockers: List<String>,
        mealCertainty: MealCertainty,
    ): ActionChoice {
        if (blockers.isNotEmpty()) {
            return ActionChoice(HarmoniaAction.BLOCKED, "blockers")
        }

        val fragility = env.correctionFragilityScore.coerceIn(0.0, 1.0)
        val exhaustion = env.postHyperExhaustionScore.coerceIn(0.0, 1.0)
        val chaotic = env.chaoticEpisodeLoad.coerceIn(0.0, 1.0)
        if (fragility >= 0.55 || exhaustion >= 0.65 || chaotic >= 0.50) {
            return ActionChoice(HarmoniaAction.STABILIZE, "fragility_or_chaos")
        }

        // MealCertainty HIGH (= former H4 bridge): digestion + strong rise beats activity protective.
        if (mealCertainty.supportsMealOverProtective) {
            return ActionChoice(HarmoniaAction.MEAL_SUPPORT, "meal_certainty_high")
        }

        if (tree.branches.activity.confidence >= 0.55 || tree.branches.postActivity.confidence >= 0.45) {
            return ActionChoice(HarmoniaAction.PROTECTIVE_REDUCTION, "activity_or_post_activity")
        }

        // Wave3 F4: MED meal support is softened when body kinetics are PEAK/TAIL heavy —
        // insulin already acting; do not escalate meal SMB on weak meal certainty alone.
        if (mealCertainty.supportsMealSupport) {
            val kinetics = env.bodyKinetics
            if (kinetics.peakHeavy || kinetics.tailHeavy) {
                return ActionChoice(
                    HarmoniaAction.STABILIZE,
                    "meal_certainty_med_kinetics_${if (kinetics.peakHeavy) "peak" else "tail"}",
                )
            }
            return ActionChoice(HarmoniaAction.MEAL_SUPPORT, "meal_certainty_med")
        }

        if (
            tree.branches.hormonalResistance.confidence >= 0.55 ||
            tree.branches.stress.confidence >= 0.55 ||
            tree.branches.insulinEffectiveness.confidence >= 0.55
        ) {
            // Lot A: before absolute hypo blocker (≥0.45 → BLOCKED), moderate hypo risk must not
            // escalate to a basal bridge under luteal/hormonal resistance (Ophe-class).
            if (tree.branches.hypoRisk.confidence >= 0.30) {
                return ActionChoice(HarmoniaAction.PROTECTIVE_REDUCTION, "hormonal_with_hypo_risk")
            }
            return ActionChoice(HarmoniaAction.BASAL_FIRST, "resistance_or_stress")
        }
        return ActionChoice(HarmoniaAction.OBSERVE, "observe_default")
    }

    /**
     * Trunk → allowed productive actions (BLOCKED / OBSERVE / STABILIZE always coherent).
     * Used for mismatch detection only — does not override [chooseActionWithReason].
     */
    internal fun isActionCoherentWithTrunk(
        trunk: GlobalPhysiologicalState,
        action: HarmoniaAction,
    ): Boolean {
        if (
            action == HarmoniaAction.BLOCKED ||
            action == HarmoniaAction.OBSERVE ||
            action == HarmoniaAction.STABILIZE
        ) {
            return true
        }
        return when (trunk) {
            GlobalPhysiologicalState.HYPO_RISK ->
                action == HarmoniaAction.PROTECTIVE_REDUCTION
            GlobalPhysiologicalState.DIGESTION_ACTIVE,
            GlobalPhysiologicalState.MEAL_PROBABLE,
            ->
                action == HarmoniaAction.MEAL_SUPPORT ||
                    action == HarmoniaAction.PROTECTIVE_REDUCTION ||
                    action == HarmoniaAction.BASAL_FIRST
            GlobalPhysiologicalState.POST_ACTIVITY ->
                action == HarmoniaAction.PROTECTIVE_REDUCTION ||
                    action == HarmoniaAction.MEAL_SUPPORT
            GlobalPhysiologicalState.RESISTANCE_PROBABLE,
            GlobalPhysiologicalState.STRESS_PROBABLE,
            ->
                action == HarmoniaAction.BASAL_FIRST ||
                    action == HarmoniaAction.PROTECTIVE_REDUCTION ||
                    action == HarmoniaAction.MEAL_SUPPORT
            GlobalPhysiologicalState.SENSOR_UNCERTAIN ->
                false
            GlobalPhysiologicalState.SENSITIVITY_INCREASED ->
                action == HarmoniaAction.PROTECTIVE_REDUCTION
            GlobalPhysiologicalState.SLEEP_RECOVERY ->
                action == HarmoniaAction.PROTECTIVE_REDUCTION
            GlobalPhysiologicalState.HYPER_RISK ->
                action == HarmoniaAction.MEAL_SUPPORT ||
                    action == HarmoniaAction.BASAL_FIRST ||
                    action == HarmoniaAction.PROTECTIVE_REDUCTION
            GlobalPhysiologicalState.STABLE,
            GlobalPhysiologicalState.MIXED,
            GlobalPhysiologicalState.UNKNOWN,
            ->
                true
        }
    }

    private fun buildDecisionBasis(
        tree: PhysiologicalTreeSnapshot,
        environment: HarmoniaDecisionEnvironment,
        action: HarmoniaAction,
        primaryReason: String,
        blockers: List<String>,
    ): HarmoniaDecisionBasis {
        val trunk = tree.trunk.globalState
        val coherent = isActionCoherentWithTrunk(trunk, action)
        val mismatch = if (coherent) {
            null
        } else {
            "action_${action.name}_not_typical_for_trunk_${trunk.name}"
        }
        return HarmoniaDecisionBasis(
            trunkState = trunk,
            trunkConfidence = tree.trunk.confidence,
            trunkRisk = tree.trunk.riskLevel,
            primaryReason = primaryReason,
            contributingBranches = contributingBranchesFor(tree, environment, action, blockers),
            actionCoherentWithTrunk = coherent,
            mismatchReason = mismatch,
        )
    }

    private fun contributingBranchesFor(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
        action: HarmoniaAction,
        blockers: List<String>,
    ): List<HarmoniaBranchContribution> {
        val b = tree.branches
        return buildList {
            if (blockers.isNotEmpty()) {
                if (b.hypoRisk.confidence >= 0.45) {
                    add(HarmoniaBranchContribution("hypoRisk", b.hypoRisk.confidence, "blocker"))
                }
                if (b.sensorTrust.confidence < 0.40) {
                    add(HarmoniaBranchContribution("sensorTrust", b.sensorTrust.confidence, "blocker"))
                }
            }
            when (action) {
                HarmoniaAction.MEAL_SUPPORT -> {
                    add(HarmoniaBranchContribution("meal", b.meal.confidence, "driver"))
                    if (b.digestion.detected || tree.trunk.globalState == GlobalPhysiologicalState.DIGESTION_ACTIVE) {
                        add(
                            HarmoniaBranchContribution(
                                "digestion",
                                b.digestion.confidence,
                                "meal_context",
                            ),
                        )
                    }
                    if (b.activity.confidence >= 0.40) {
                        add(HarmoniaBranchContribution("activity", b.activity.confidence, "competing"))
                    }
                }
                HarmoniaAction.PROTECTIVE_REDUCTION -> {
                    if (b.activity.confidence >= 0.40) {
                        add(HarmoniaBranchContribution("activity", b.activity.confidence, "driver"))
                    }
                    if (b.postActivity.confidence >= 0.40) {
                        add(HarmoniaBranchContribution("postActivity", b.postActivity.confidence, "driver"))
                    }
                    if (b.hypoRisk.confidence >= 0.30) {
                        add(HarmoniaBranchContribution("hypoRisk", b.hypoRisk.confidence, "context"))
                    }
                }
                HarmoniaAction.BASAL_FIRST -> {
                    if (b.hormonalResistance.confidence >= 0.40) {
                        add(HarmoniaBranchContribution("hormonalResistance", b.hormonalResistance.confidence, "driver"))
                    }
                    if (b.stress.confidence >= 0.40) {
                        add(HarmoniaBranchContribution("stress", b.stress.confidence, "driver"))
                    }
                    if (b.insulinEffectiveness.confidence >= 0.40) {
                        add(HarmoniaBranchContribution("insulinEffectiveness", b.insulinEffectiveness.confidence, "driver"))
                    }
                }
                HarmoniaAction.STABILIZE -> {
                    add(HarmoniaBranchContribution("trunk", tree.trunk.confidence, "stabilize"))
                }
                HarmoniaAction.BLOCKED,
                HarmoniaAction.OBSERVE,
                -> {
                    add(HarmoniaBranchContribution("trunk", tree.trunk.confidence, "context"))
                }
            }
        }.distinctBy { it.name }
    }

    private fun buildRationale(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
        action: HarmoniaAction,
        blockers: List<String>,
        basis: HarmoniaDecisionBasis,
    ): List<String> =
        buildList {
            add("tree=${tree.trunk.globalState.name} conf=${pct(tree.trunk.confidence)} risk=${tree.trunk.riskLevel.name}")
            add("bg=${env.currentBgMgdl.roundToInt()} delta=${fmt(env.deltaMgdl5m)} iob=${fmt(env.iobU)}/${fmt(env.maxIobU)}")
            add("primary_reason=${basis.primaryReason}")
            if (blockers.isNotEmpty()) {
                add("blocked=${blockers.joinToString(",")}")
            } else {
                add("simulation_action=${action.name.lowercase(Locale.US)}")
                // Stable marker retained for JSONL/tests: HIGH meal certainty bridge (ex-H4).
                if (basis.primaryReason == "meal_certainty_high") {
                    add("h4_meal_rise_bridge")
                    add("meal_certainty_high")
                }
            }
            if (!basis.actionCoherentWithTrunk) {
                add("HARMONIA_BRANCH_MISMATCH=${basis.mismatchReason}")
            }
        }

    private fun buildSummary(
        action: HarmoniaAction,
        branch: String,
        eligible: Boolean,
        basal: Double,
        smb: Double,
        blockers: List<String>,
        basis: HarmoniaDecisionBasis,
    ): String {
        if (!eligible) {
            return "Harmonia sim: blocked $branch | ${blockers.joinToString(",")}"
        }
        val mismatch = if (!basis.actionCoherentWithTrunk) " | MISMATCH" else ""
        return "Harmonia sim: ${action.name.lowercase(Locale.US)} $branch" +
            " (${basis.primaryReason}) | basal ${fmt(basal)}U/h | smb ${fmt(smb)}U$mismatch"
    }

    private fun roundToStep(value: Double, step: Double): Double {
        if (!value.isFinite()) return 0.0
        if (!step.isFinite() || step <= 0.0) return value
        return ((value / step).roundToInt() * step).coerceAtLeast(0.0)
    }

    private fun fmt(value: Double): String = aimiFmt2(value)

    private fun pct(value: Double): String = "${(value.coerceIn(0.0, 1.0) * 100.0).roundToInt()}%"
}
