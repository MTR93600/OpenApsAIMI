package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit
import kotlin.math.max

/**
 * Single meal-language for the decision cascade (Tree → Harmonia → Auditor).
 * See [docs/AIMI_DECISION_CASCADE_CONTRACT.md] §6.
 */
enum class MealCertaintyLevel {
    NONE,
    LOW,
    MED,
    HIGH,
}

enum class MealCertaintyTreeState {
    NONE,
    MEAL_PROBABLE,
    DIGESTION_ACTIVE,
}

enum class MealRiseGeometry {
    FALLING,
    WEAK,
    OK,
}

enum class MealTerminalsAgree {
    OK,
    PKPD_FLOOR_CONFLICT,
    HYPO_CONFLICT,
    UNKNOWN,
}

data class MealCertainty(
    val level: MealCertaintyLevel,
    val treeState: MealCertaintyTreeState,
    val absorptionPhase: MealAbsorptionPhase,
    val riseGeometry: MealRiseGeometry,
    val terminalsAgree: MealTerminalsAgree,
    val effortVeto: Boolean,
    val softCorroboration: Boolean,
    val reasons: List<String> = emptyList(),
) {
    /** H4 bridge: digestion + strong rise → meal support beats protective. */
    val supportsMealOverProtective: Boolean
        get() = level == MealCertaintyLevel.HIGH

    /** Productive meal support without beating activity protective. */
    val supportsMealSupport: Boolean
        get() = level == MealCertaintyLevel.HIGH || level == MealCertaintyLevel.MED

    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("level", level.name)
            put("tree_state", treeState.name)
            put("absorption_phase", absorptionPhase.name)
            put("rise_geometry", riseGeometry.name)
            put("terminals_agree", terminalsAgree.name)
            put("effort_veto", effortVeto)
            put("soft_corroboration", softCorroboration)
            put("supports_meal_over_protective", supportsMealOverProtective)
            put("supports_meal_support", supportsMealSupport)
            put("reasons", JsonArray(reasons.map { JsonPrimitive(it) }))
            put("source", "meal_certainty_v1")
        }

    companion object {
        val NONE: MealCertainty =
            MealCertainty(
                level = MealCertaintyLevel.NONE,
                treeState = MealCertaintyTreeState.NONE,
                absorptionPhase = MealAbsorptionPhase.NONE,
                riseGeometry = MealRiseGeometry.WEAK,
                terminalsAgree = MealTerminalsAgree.UNKNOWN,
                effortVeto = false,
                softCorroboration = false,
                reasons = listOf("absent"),
            )
    }
}

object MealCertaintyBuilder {

    /** Deep-hyper floor (mg/dL) for effort-veto override on a strong digestion rise. */
    internal const val EFFORT_VETO_OVERRIDE_MIN_BG_MGDL = 200.0

    /** Strong 5‑min rise (mg/dL) required with an active absorption wave to override effort_veto. */
    internal const val EFFORT_VETO_OVERRIDE_MIN_DELTA_MGDL5M = 4.0

    /**
     * 5-minute rise (mg/dL) required to override a **stale** effort veto before BG reaches
     * [EFFORT_VETO_OVERRIDE_MIN_BG_MGDL].
     *
     * Set from the corpus. Over 952 pooled ticks, labelling `STRESS_CORTISOL` episodes from the glucose
     * trajectory rather than from the classifier's own opinion, genuine endogenous ramps peaked at
     * **9.1** mg/dL per 5 min while food episodes reached 12.9 and 20.1. A threshold of 10 or above
     * separated them with **0 false positives on 11 genuine ramps**. The same figure is used here so a
     * dawn ramp or a stress response cannot open the meal channel.
     */
    internal const val EFFORT_VETO_ANTICIPATED_MIN_DELTA_MGDL5M = 10.0

    /**
     * Short-average rise (mg/dL per 5 min) that must corroborate
     * [EFFORT_VETO_ANTICIPATED_MIN_DELTA_MGDL5M].
     *
     * The second, independent window. A single CGM sample can produce a large 5-minute delta from noise,
     * a compression artefact recovering, or a sensor restart; the short average cannot. Both must hold,
     * so the override needs a rise that two windows agree on.
     */
    internal const val EFFORT_VETO_ANTICIPATED_MIN_SHORT_AVG_MGDL5M = 5.0

    /**
     * Lower bound on the effort SMB multiplier while the meal is certain ([MealCertaintyLevel.HIGH]).
     *
     * The effort reduction is a hypo protection and stays in force — on a certain meal it may take at
     * most a quarter of the dose instead of up to 55 %.
     */
    internal const val EFFORT_SMB_FLOOR_CONFIRMED_MEAL = 0.75

    /**
     * Effort SMB multiplier to apply, after the confirmed-meal floor.
     *
     * Reduction-only in both arms: the result is never above 1.0, and never above [requestedFactor]
     * unless the meal is certain. Measured on 2026-08-09/10: two lunches ran the identical cap chain
     * and differed only in this multiplier — absent on 2026-08-09 (14.09 U delivered), 0.45–0.56 on
     * 14 ticks of 2026-08-10 (6.81 U delivered, BG peaked at 269) after a walk to lunch. See
     * docs/AIMI_NEXT_SESSION.md Part A-quater.
     *
     * @param certainty the tick's meal certainty, or null when it was not evaluated.
     * @param requestedFactor what `EffortActivityBelief` asked for.
     */
    fun effortSmbFactorFor(certainty: MealCertainty?, requestedFactor: Double): Double {
        if (!requestedFactor.isFinite()) return 1.0
        val requested = requestedFactor.coerceIn(0.0, 1.0)
        if (certainty?.level != MealCertaintyLevel.HIGH) return requested
        return max(requested, EFFORT_SMB_FLOOR_CONFIRMED_MEAL)
    }

    data class Input(
        val trunkState: GlobalPhysiologicalState?,
        val mealBranchConfidence: Double = 0.0,
        val digestionDetected: Boolean = false,
        val absorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        val bgMgdl: Double,
        val deltaMgdl5m: Double,
        val targetBgMgdl: Double?,
        val cobG: Double = 0.0,
        val mealRiseConfirmedLegacy: Boolean = false,
        val effortVeto: Boolean = false,
        /**
         * Short-average glucose rise, mg/dL per 5 min. Second window for the anticipated-rise override.
         *
         * Defaults to 0.0, so a caller that does not supply it can never open the override — the
         * anticipated clause needs both windows.
         */
        val shortAvgDeltaMgdl5m: Double = 0.0,
        /**
         * True when the effort belief is reporting movement **now**, false when it is only a memory.
         *
         * The distinction is the whole safety of the anticipated-rise override: a walk in progress keeps
         * the full effort reduction whatever glucose does, and only a stale memory can be overridden.
         * Defaults to `true` — the conservative value — so a caller that does not supply it keeps the
         * previous behaviour and the override stays shut.
         */
        val effortLive: Boolean = true,
        val softCorroboration: Boolean = false,
        val pkpdEventualMgdl: Double? = null,
        val scenarioTerminalMgdl: Double? = null,
        val scenarioPathMinMgdl: Double? = null,
        val scenarioPathMinHitFloor: Boolean = false,
    )

    fun evaluate(input: Input): MealCertainty {
        val reasons = mutableListOf<String>()
        val treeState = treeStateOf(input.trunkState)
        val rise = riseGeometryOf(input.deltaMgdl5m)
        val terminals = terminalsAgreeOf(input)
        val phase = input.absorptionPhase

        if (input.effortVeto) reasons.add("effort_veto")
        if (input.softCorroboration) reasons.add("soft_hr_corroboration")
        when (rise) {
            MealRiseGeometry.FALLING -> reasons.add("rise_falling")
            MealRiseGeometry.WEAK -> reasons.add("rise_weak")
            MealRiseGeometry.OK -> reasons.add("rise_ok")
        }
        when (terminals) {
            MealTerminalsAgree.HYPO_CONFLICT -> reasons.add("terminals_hypo_conflict")
            MealTerminalsAgree.PKPD_FLOOR_CONFLICT -> reasons.add("terminals_pkpd_floor_conflict")
            MealTerminalsAgree.OK -> reasons.add("terminals_ok")
            MealTerminalsAgree.UNKNOWN -> reasons.add("terminals_unknown")
        }

        val level = resolveLevel(
            input = input,
            treeState = treeState,
            rise = rise,
            terminals = terminals,
            phase = phase,
            reasons = reasons,
        )

        return MealCertainty(
            level = level,
            treeState = treeState,
            absorptionPhase = phase,
            riseGeometry = rise,
            terminalsAgree = terminals,
            effortVeto = input.effortVeto,
            softCorroboration = input.softCorroboration,
            reasons = reasons.distinct(),
        )
    }

    fun fromTreeAndEnvironment(
        tree: PhysiologicalTreeSnapshot,
        env: HarmoniaDecisionEnvironment,
        absorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        effortVeto: Boolean = false,
        softCorroboration: Boolean = false,
        pkpdEventualMgdl: Double? = null,
        scenarioTerminalMgdl: Double? = null,
        scenarioPathMinMgdl: Double? = null,
        scenarioPathMinHitFloor: Boolean = false,
    ): MealCertainty =
        evaluate(
            Input(
                trunkState = tree.trunk.globalState,
                mealBranchConfidence = tree.branches.meal.confidence,
                digestionDetected = tree.branches.digestion.detected,
                absorptionPhase = absorptionPhase,
                bgMgdl = env.currentBgMgdl,
                deltaMgdl5m = env.deltaMgdl5m,
                targetBgMgdl = env.targetBgMgdl,
                cobG = env.cobG,
                mealRiseConfirmedLegacy = env.mealRiseConfirmed,
                effortVeto = effortVeto,
                softCorroboration = softCorroboration,
                pkpdEventualMgdl = pkpdEventualMgdl,
                scenarioTerminalMgdl = scenarioTerminalMgdl,
                scenarioPathMinMgdl = scenarioPathMinMgdl,
                scenarioPathMinHitFloor = scenarioPathMinHitFloor,
            ),
        )

    internal fun softCorroborationFromPhysio(physioLive: PhysioLiveDigest?): Boolean {
        if (physioLive == null) return false
        val hr = physioLive.hrNowBpm
        val rhr = physioLive.rhrRestingBpm
        if (hr <= 0 || rhr <= 0) return false
        val idle = physioLive.activityState.equals("IDLE", ignoreCase = true) ||
            physioLive.activityState.equals("STILL", ignoreCase = true)
        val stepsLow = physioLive.stepsLast15m < 80
        // Mild elevation vs RHR while idle — never a lead signal alone.
        return idle && stepsLow && hr >= rhr + 12
    }

    private fun treeStateOf(trunk: GlobalPhysiologicalState?): MealCertaintyTreeState =
        when (trunk) {
            GlobalPhysiologicalState.DIGESTION_ACTIVE -> MealCertaintyTreeState.DIGESTION_ACTIVE
            GlobalPhysiologicalState.MEAL_PROBABLE -> MealCertaintyTreeState.MEAL_PROBABLE
            else -> MealCertaintyTreeState.NONE
        }

    private fun riseGeometryOf(deltaMgdl5m: Double): MealRiseGeometry =
        when {
            !deltaMgdl5m.isFinite() -> MealRiseGeometry.WEAK
            deltaMgdl5m < 0.0 -> MealRiseGeometry.FALLING
            deltaMgdl5m >= HarmoniaDecisionEngine.H4_MIN_RISING_DELTA_MGDL -> MealRiseGeometry.OK
            else -> MealRiseGeometry.WEAK
        }

    private fun terminalsAgreeOf(input: Input): MealTerminalsAgree {
        val pathMin = input.scenarioPathMinMgdl
        if (pathMin != null && pathMin.isFinite()) {
            if (input.scenarioPathMinHitFloor || pathMin < ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL) {
                return MealTerminalsAgree.HYPO_CONFLICT
            }
        }
        val pkpd = input.pkpdEventualMgdl
        val scn = input.scenarioTerminalMgdl
        if (
            pkpd != null && scn != null && pkpd.isFinite() && scn.isFinite() &&
            pkpd < 50.0 && scn - pkpd >= ClampPkpdScenarioReconcile.MIN_DIVERGENCE_MGDL
        ) {
            return MealTerminalsAgree.PKPD_FLOOR_CONFLICT
        }
        if (pkpd == null && scn == null && pathMin == null) {
            return MealTerminalsAgree.UNKNOWN
        }
        return MealTerminalsAgree.OK
    }

    private fun resolveLevel(
        input: Input,
        treeState: MealCertaintyTreeState,
        rise: MealRiseGeometry,
        terminals: MealTerminalsAgree,
        phase: MealAbsorptionPhase,
        reasons: MutableList<String>,
    ): MealCertaintyLevel {
        if (terminals == MealTerminalsAgree.HYPO_CONFLICT) {
            reasons.add("level_none_hypo_terminals")
            return MealCertaintyLevel.NONE
        }
        // Effort veto blocks undeclared HIGH/MED; declared COB may keep MED.
        val effortBlocksMeal = input.effortVeto && input.cobG < 3.0
        if (effortBlocksMeal && treeState == MealCertaintyTreeState.NONE && !phase.isActive) {
            reasons.add("level_none_effort")
            return MealCertaintyLevel.NONE
        }

        val target = input.targetBgMgdl
        val aboveMealBand =
            target != null &&
                target.isFinite() &&
                input.bgMgdl.isFinite() &&
                input.bgMgdl > target + PostHypoAggressiveRiseExit.TARGET_MARGIN_MGDL

        val digestionRiseCore =
            treeState == MealCertaintyTreeState.DIGESTION_ACTIVE &&
                rise == MealRiseGeometry.OK &&
                aboveMealBand &&
                terminals != MealTerminalsAgree.HYPO_CONFLICT

        // Undeclared-meal hyper plateaus (e.g. 25/07 14:42): postprandial HR/steps often trip
        // effort_veto while the trunk is already DIGESTION_ACTIVE + FIRST_WAVE with a strong rise.
        // That pinned MealCertainty to LOW → Harmonia PROTECTIVE_REDUCTION (smb_factor=0) despite
        // MEAL_UNDECLARED_FAST. Override only on deep hyper + strong Δ + active absorption wave;
        // milder effort+meal cases stay LOW (see effortVeto_blocksHighAndMedWhenUndeclared).
        val effortVetoOverriddenByStrongMealRise =
            effortBlocksMeal &&
                digestionRiseCore &&
                phase.isActive &&
                input.bgMgdl >= EFFORT_VETO_OVERRIDE_MIN_BG_MGDL &&
                input.deltaMgdl5m >= EFFORT_VETO_OVERRIDE_MIN_DELTA_MGDL5M

        // The same override, reachable **before** the excursion instead of after it.
        //
        // The clause above is gated on BG >= 200, and the MED arm below is closed by `effortBlocksMeal`,
        // so while the effort veto holds, HIGH is mathematically unreachable under BG 200 and the level
        // falls straight through to LOW. Measured over 20 rise episodes in the support-package corpus:
        // HIGH arrives at a median of 10 minutes after onset and a median BG of 144, never at all in
        // 8 of 20; the 0.75 effort floor it arms fires at BG 204-212 and never at all in **17 of 20**.
        // On the 2026-08-14 lunch the level was LOW through BG 128 -> 199 rising +27 mg/dL per 5 min,
        // with the effort multiplier at x0.45, and only reached HIGH at BG 204 — 30 minutes late.
        //
        // Level is the wrong evidence for "this is a meal". Rise magnitude, corroborated, is better, and
        // it is available at the first tick. This clause substitutes for the BG floor:
        //
        // - **two independent rise windows must agree** ([EFFORT_VETO_ANTICIPATED_MIN_DELTA_MGDL5M] on
        //   the 5-minute delta and [EFFORT_VETO_ANTICIPATED_MIN_SHORT_AVG_MGDL5M] on the short average),
        //   so a single noisy sample cannot open it;
        // - **the effort must be a memory, not live movement** (`!input.effortLive`). This is the term
        //   that keeps the protection intact: a walk in progress still produces the full effort
        //   reduction, at any rise. It is aimed squarely at the measured defect — 61 of 82 effort-reduced
        //   ticks had `effort = 0.00`, no live movement at all, on a 120-minute memory of a walk to
        //   lunch;
        // - everything in `digestionRiseCore` still holds, including `aboveMealBand`
        //   (BG > target + 30) and `terminals != HYPO_CONFLICT`, and the absorption phase must be active.
        //
        // Cost on the hypoglycaemia ticks: nil, structurally. On the 39 ticks below BG 75 in the corpus
        // (min 45) the level was NONE on every one, glucose was falling, and `aboveMealBand` is false
        // below about 130 mg/dL — so `digestionRiseCore` cannot hold and this clause cannot fire.
        val effortVetoOverriddenByAnticipatedMealRise =
            effortBlocksMeal &&
                !input.effortLive &&
                digestionRiseCore &&
                phase.isActive &&
                input.deltaMgdl5m >= EFFORT_VETO_ANTICIPATED_MIN_DELTA_MGDL5M &&
                input.shortAvgDeltaMgdl5m >= EFFORT_VETO_ANTICIPATED_MIN_SHORT_AVG_MGDL5M

        val effortVetoOverridden =
            effortVetoOverriddenByStrongMealRise || effortVetoOverriddenByAnticipatedMealRise

        val digestionHigh = digestionRiseCore && (!effortBlocksMeal || effortVetoOverridden)

        if (digestionHigh) {
            reasons.add(
                when {
                    effortVetoOverriddenByStrongMealRise      -> "level_high_digestion_overrides_effort_veto"
                    effortVetoOverriddenByAnticipatedMealRise -> "level_high_digestion_anticipated_rise"
                    else                                     -> "level_high_digestion_rise"
                },
            )
            return MealCertaintyLevel.HIGH
        }

        val mealContext =
            treeState == MealCertaintyTreeState.DIGESTION_ACTIVE ||
                treeState == MealCertaintyTreeState.MEAL_PROBABLE ||
                phase.isActive ||
                input.mealBranchConfidence >= 0.55 ||
                (input.cobG >= 3.0 && input.mealBranchConfidence >= 0.50)

        if (
            mealContext &&
            (rise == MealRiseGeometry.OK || rise == MealRiseGeometry.WEAK) &&
            !effortBlocksMeal &&
            terminals != MealTerminalsAgree.HYPO_CONFLICT
        ) {
            reasons.add("level_med_meal_context")
            return MealCertaintyLevel.MED
        }

        if (
            mealContext ||
            input.mealRiseConfirmedLegacy ||
            input.mealBranchConfidence >= 0.45
        ) {
            reasons.add("level_low_weak_or_vetoed")
            return MealCertaintyLevel.LOW
        }

        reasons.add("level_none")
        return MealCertaintyLevel.NONE
    }
}
