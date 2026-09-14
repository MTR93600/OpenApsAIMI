package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorMetrics
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Computes context-aware preference adjustments with graduated dosing.
 *
 * Bidirectional model:
 * - Hyper contexts increase aggression (with hypo guardrails).
 * - [AimiTuningContext.HYPO_GUARD] decreases aggression and can disable tube / relief when lows are frequent.
 * - [AimiTuningContext.MIXED_BALANCE] (Auto) applies hypo reductions first, then limited hyper tweaks on non-conflicting keys.
 */
object TuningContextEngine {

    private const val EPS = 0.001

    fun parseContext(raw: String?): AimiTuningContext =
        runCatching { AimiTuningContext.valueOf(raw?.trim()?.uppercase() ?: "") }
            .getOrDefault(AimiTuningContext.AUTO_BALANCE)

    /** Shown instead of a plan when the period could not be measured. */
    const val NO_DATA_BLOCK = "Tuning blocked: not enough glucose data in this period to propose a change."

    fun computePlan(
        requestedContext: AimiTuningContext,
        metrics: AdvisorMetrics,
        preferences: Preferences,
        t3cBrittleMode: Boolean,
    ): TuningPlan {
        // These changes reach insulin settings, so an unknown period must produce no change at all.
        val hypo = metrics.timeBelow70
        val hyper = metrics.timeAbove180
        if (metrics.isInsufficient || hypo == null || hyper == null) {
            return TuningPlan(requestedContext, requestedContext, TuningStepTier.MICRO, emptyList(), NO_DATA_BLOCK)
        }
        val effective = when (requestedContext) {
            AimiTuningContext.AUTO_BALANCE -> resolveAutoContext(metrics)
            else -> requestedContext
        }
        val tier = dominantTier(effective, hypo, hyper)
        val blocked = guardBlock(effective, metrics, hypo, hyper)
        if (blocked != null) {
            return TuningPlan(requestedContext, effective, tier, emptyList(), blocked)
        }
        val changes = when (effective) {
            AimiTuningContext.MEAL_RISE -> buildMealRiseChanges(hypo, hyper, preferences, tier, t3cBrittleMode)
            AimiTuningContext.HYPO_GUARD -> buildHypoGuardChanges(hypo, preferences, tier, t3cBrittleMode)
            AimiTuningContext.HYPER_STABLE -> buildHyperStableChanges(hypo, preferences, tier, t3cBrittleMode)
            AimiTuningContext.MIXED_BALANCE -> buildMixedBalanceChanges(hypo, hyper, preferences, t3cBrittleMode)
            AimiTuningContext.AUTO_BALANCE -> emptyList()
        }
        return TuningPlan(requestedContext, effective, tier, changes)
    }

    /**
     * Picks the context that best matches the measured period. An unknown period falls back to
     * [AimiTuningContext.HYPO_GUARD], the safest of the four, but `computePlan` stops before this
     * is ever used for a real change.
     */
    fun resolveAutoContext(metrics: AdvisorMetrics): AimiTuningContext {
        val hypo = metrics.timeBelow70 ?: return AimiTuningContext.HYPO_GUARD
        val hyper = metrics.timeAbove180 ?: return AimiTuningContext.HYPO_GUARD
        val hypoSignificant = hypo >= 0.04
        val hyperSignificant = hyper >= 0.12
        val hypoDominates = hypo >= 0.055 && hypo * 1.15 >= hyper
        val hyperMealPattern = hyper >= 0.20 && hypo < 0.045
        val hyperDominatesGeneral = hyper >= 0.12 && hypo < 0.035 && hyper > hypo * 1.5

        return when {
            hypoDominates -> AimiTuningContext.HYPO_GUARD
            hyperMealPattern -> AimiTuningContext.MEAL_RISE
            hypoSignificant && hyperSignificant && !hypoDominates && !hyperMealPattern && !hyperDominatesGeneral ->
                AimiTuningContext.MIXED_BALANCE
            hyperDominatesGeneral || (hyperSignificant && hypo < 0.05) -> AimiTuningContext.HYPER_STABLE
            hypoSignificant -> AimiTuningContext.HYPO_GUARD
            else -> AimiTuningContext.HYPER_STABLE
        }
    }

    fun hyperTier(timeAbove180: Double): TuningStepTier = when {
        timeAbove180 >= 0.40 -> TuningStepTier.STRONG
        timeAbove180 >= 0.28 -> TuningStepTier.MODERATE
        timeAbove180 >= 0.18 -> TuningStepTier.MICRO
        else -> TuningStepTier.MICRO
    }

    fun hypoTier(timeBelow70: Double): TuningStepTier = when {
        timeBelow70 >= 0.08 -> TuningStepTier.STRONG
        timeBelow70 >= 0.055 -> TuningStepTier.MODERATE
        timeBelow70 >= 0.035 -> TuningStepTier.MICRO
        else -> TuningStepTier.MICRO
    }

    private fun dominantTier(context: AimiTuningContext, hypo: Double, hyper: Double): TuningStepTier =
        when (context) {
            AimiTuningContext.HYPO_GUARD -> hypoTier(hypo)
            AimiTuningContext.MEAL_RISE,
            AimiTuningContext.HYPER_STABLE,
            -> hyperTier(hyper)
            AimiTuningContext.MIXED_BALANCE -> maxTier(
                hypoTier(hypo),
                hyperTier(hyper),
            )
            AimiTuningContext.AUTO_BALANCE -> TuningStepTier.MICRO
        }

    private fun maxTier(a: TuningStepTier, b: TuningStepTier): TuningStepTier =
        if (tierRank(a) >= tierRank(b)) a else b

    private fun capTier(tier: TuningStepTier, max: TuningStepTier): TuningStepTier =
        if (tierRank(tier) <= tierRank(max)) tier else max

    private fun tierRank(tier: TuningStepTier): Int = when (tier) {
        TuningStepTier.MICRO -> 0
        TuningStepTier.MODERATE -> 1
        TuningStepTier.STRONG -> 2
    }

    private fun guardBlock(context: AimiTuningContext, metrics: AdvisorMetrics, hypo: Double, hyper: Double): String? {
        when (context) {
            AimiTuningContext.MEAL_RISE -> {
                if (hypo > 0.06) {
                    return "Meal-rise tuning blocked: hypo burden (${pct(hypo)}% below 70) is too high."
                }
                if (hyper < 0.12) {
                    return "Meal-rise tuning blocked: hyper burden (${pct(hyper)}% above 180) is too low."
                }
            }
            AimiTuningContext.HYPO_GUARD -> {
                if (hypo < 0.025) {
                    return "Hypo guard blocked: time below 70 (${pct(hypo)}%) is already low."
                }
            }
            AimiTuningContext.HYPER_STABLE -> {
                if (hypo >= 0.045) {
                    return "Hyper tuning blocked: hypo burden (${pct(hypo)}% below 70) — use Hypo guard or Auto."
                }
                val tir = metrics.tir70_180
                if (hyper < 0.10 && tir != null && tir >= 0.72) {
                    return "Hyper tuning blocked: control is already stable (TIR ${pct(tir)}%)."
                }
            }
            AimiTuningContext.MIXED_BALANCE -> {
                if (hypo < 0.035 && hyper < 0.10) {
                    return "Mixed tuning blocked: neither hypo nor hyper burden is significant enough."
                }
            }
            AimiTuningContext.AUTO_BALANCE -> Unit
        }
        return null
    }

    private fun buildMealRiseChanges(
        hypo: Double,
        hyper: Double,
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        // Complements Hyper Trajectory Release (HTR): when OApsAIMIHyperTrajectoryRelease is on with Autodrive V3,
        // prefer HTR SMB floors over large MaxSMB jumps — avoid double-counting meal-rise aggression.
        val cappedTier = if (hypo >= 0.04) {
            capTier(tier, TuningStepTier.MICRO)
        } else {
            tier
        }
        val moderateHypoRisk = hypo >= 0.04
        val out = mutableListOf<TuningChange>()
        val smbStep = step(cappedTier, micro = 0.05, moderate = 0.10, strong = 0.20)
        val highBgStep = step(cappedTier, micro = 0.05, moderate = 0.12, strong = 0.25)
        val tubeAggStep = step(cappedTier, micro = 0.08, moderate = 0.15, strong = 0.25)
        val reliefStep = step(cappedTier, micro = 0.03, moderate = 0.05, strong = 0.08)
        val maxIobFactorStep = step(cappedTier, micro = 0.04, moderate = 0.08, strong = 0.12)
        val maxIobExtraStep = step(cappedTier, micro = 0.25, moderate = 0.50, strong = 1.0)
        val lunchStep = step(cappedTier, micro = 0.05, moderate = 0.10, strong = 0.15)

        if (!moderateHypoRisk) {
            proposeBoolean(
                out, preferences, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, true, cappedTier,
                "Enable pragmatic relief for explicit meal/high-rise SMB intent.",
            )
        }
        val htrComplementsMealRise = preferences.get(BooleanKey.OApsAIMIautoDriveActive) &&
            preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease)
        if (!htrComplementsMealRise) {
            proposeDoubleIncrease(
                out, preferences, DoubleKey.OApsAIMIHighBGMaxSMB, highBgStep, cappedTier,
                "Raise High-BG Max SMB for post-meal corrections.",
            )
            proposeDoubleIncrease(
                out, preferences, DoubleKey.OApsAIMIMaxSMB, smbStep, cappedTier,
                "Raise Max SMB slightly for meal rises.",
            )
        }
        if (!moderateHypoRisk) {
            proposeDoubleIncrease(
                out, preferences, DoubleKey.OApsAIMILunchFactor, lunchStep, cappedTier,
                "Increase lunch mode factor for midday rises.",
            )
            proposeDoubleIncrease(
                out, preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, reliefStep, cappedTier,
                "Raise PKPD relief floor to preserve SMB in priority contexts.",
            )
            proposeDoubleIncrease(
                out, preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, maxIobFactorStep, cappedTier,
                "Add priority MaxIOB headroom during sustained hyper.",
            )
            proposeDoubleIncrease(
                out, preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, maxIobExtraStep, cappedTier,
                "Add priority MaxIOB extra units during sustained hyper.",
            )
            proposeDoubleIncrease(
                out, preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, reliefStep, cappedTier,
                "Raise Red Carpet restore so explicit contexts recover SMB before hard caps.",
            )
        } else {
            appendMealFactorReductions(out, preferences, hypoTier(hypo))
        }

        if (!moderateHypoRisk && hyper >= 0.18 && hypo < 0.04) {
            proposeBoolean(
                out, preferences, BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled, true, cappedTier,
                "Enable straight-line tube for trajectory-aware meal corrections.",
            )
            proposeDoubleIncrease(
                out, preferences, DoubleKey.AimiTubeAggressiveness, tubeAggStep, cappedTier,
                "Increase tube aggressiveness for faster meal rise control.",
            )
        }

        if (!t3cBrittle && !moderateHypoRisk) {
            proposeTailDampingWeaken(
                out, preferences,
                step(cappedTier, micro = 0.04, moderate = 0.06, strong = 0.10), cappedTier,
                "Weaken SMB tail damping (raise tail floor) when hypers dominate and hypos are rare.",
            )
        }
        return out
    }

    private fun buildHypoGuardChanges(
        hypo: Double,
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val out = mutableListOf<TuningChange>()
        appendHypoAggressionReductions(out, preferences, tier, t3cBrittle)
        if (hypo >= 0.04) {
            appendMealFactorReductions(out, preferences, tier)
        }
        appendHypoStrongSafetyDisables(out, preferences, hypo, tier)
        return out
    }

    private fun buildMixedBalanceChanges(
        hypo: Double,
        hyper: Double,
        preferences: Preferences,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val hypoT = hypoTier(hypo)
        val hyperT = hyperTier(hyper)
        val out = mutableListOf<TuningChange>()

        appendHypoAggressionReductions(out, preferences, hypoT, t3cBrittle)
        if (hypo >= 0.04) {
            appendMealFactorReductions(out, preferences, hypoT)
        }
        appendHypoStrongSafetyDisables(out, preferences, hypo, hypoT)

        // Hyper side: non-conflicting keys only; never raise SMB caps when hypos are significant.
        if (hyper >= 0.14 && hypoT != TuningStepTier.STRONG) {
            proposeBoolean(
                out, preferences, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, true, hyperT,
                "Enable pragmatic relief for post-meal routing without raising SMB caps (mixed pattern).",
            )
            if (hypo < 0.05) {
                proposeDoubleIncrease(
                    out, preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
                    step(hyperT, micro = 0.02, moderate = 0.04, strong = 0.06), hyperT,
                    "Slightly raise Red Carpet restore for hyper leg of mixed pattern (hypos not dominant).",
                )
            }
        }
        return out
    }

    private fun buildHyperStableChanges(
        hypo: Double,
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val out = mutableListOf<TuningChange>()
        val smbStep = step(tier, micro = 0.05, moderate = 0.08, strong = 0.15)
        val reliefStep = step(tier, micro = 0.03, moderate = 0.05, strong = 0.06)

        proposeBoolean(
            out, preferences, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, true, tier,
            "Enable pragmatic relief for sustained hyper patterns.",
        )
        proposeDoubleIncrease(
            out, preferences, DoubleKey.OApsAIMIMaxSMB, smbStep, tier,
            "Moderate Max SMB increase for general hyper control.",
        )
        proposeDoubleIncrease(
            out, preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, reliefStep, tier,
            "Raise PKPD relief minimum for clearer correction intent.",
        )
        if (!t3cBrittle && hypo < 0.035) {
            proposeTailDampingWeaken(
                out, preferences,
                step(tier, micro = 0.03, moderate = 0.05, strong = 0.08), tier,
                "Slightly weaken tail damping (raise tail floor) when hypers dominate.",
            )
        }
        return out
    }

    private fun appendHypoAggressionReductions(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ) {
        val smbStep = step(tier, micro = 0.05, moderate = 0.10, strong = 0.15)
        val reliefStep = step(tier, micro = 0.03, moderate = 0.05, strong = 0.08)
        val maxIobFactorStep = step(tier, micro = 0.04, moderate = 0.08, strong = 0.12)
        val maxIobExtraStep = step(tier, micro = 0.25, moderate = 0.50, strong = 1.0)
        val tubeAggStep = step(tier, micro = 0.08, moderate = 0.12, strong = 0.20)
        val floorStep = step(tier, micro = 2.0, moderate = 4.0, strong = 6.0)

        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMIMaxSMB, smbStep, tier,
            "Lower Max SMB while hypo exposure is elevated.",
        )
        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMIHighBGMaxSMB, smbStep, tier,
            "Lower High-BG Max SMB to reduce correction stacking.",
        )
        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor, maxIobFactorStep, tier,
            "Reduce priority MaxIOB factor during hypo burden.",
        )
        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU, maxIobExtraStep, tier,
            "Trim priority MaxIOB extra units during hypo burden.",
        )
        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, reliefStep, tier,
            "Lower PKPD relief floor to soften aggressive SMB during lows.",
        )
        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold, reliefStep, tier,
            "Lower Red Carpet restore to reduce late SMB snap-back.",
        )

        if (!t3cBrittle) {
            proposeTailDampingStrengthen(
                out, preferences,
                step(tier, micro = 0.05, moderate = 0.08, strong = 0.12), tier,
                "Strengthen SMB tail damping (lower tail floor) to soften late corrections.",
            )
        }

        if (preferences.get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)) {
            proposeDoubleDecrease(
                out, preferences, DoubleKey.AimiTubeAggressiveness, tubeAggStep, tier,
                "Reduce tube aggressiveness while hypos are frequent.",
            )
            proposeDoubleIncrease(
                out, preferences, DoubleKey.AimiTubeHypoFloorMgdl, floorStep, tier,
                "Raise tube hypo floor for safer trajectory control.",
            )
        }
    }

    private fun appendMealFactorReductions(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        tier: TuningStepTier,
    ) {
        val mealStep = step(tier, micro = 0.05, moderate = 0.10, strong = 0.20)
        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMILunchFactor, mealStep, tier,
            "Lower lunch mode factor — hypos often follow post-prandial corrections.",
        )
        proposeDoubleDecrease(
            out, preferences, DoubleKey.OApsAIMIDinnerFactor, mealStep, tier,
            "Lower dinner mode factor — hypos often follow post-prandial corrections.",
        )
    }

    private fun appendHypoStrongSafetyDisables(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        hypo: Double,
        tier: TuningStepTier,
    ) {
        if (hypo >= 0.045 && preferences.get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)) {
            proposeBoolean(
                out, preferences, BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled, false, tier,
                "Disable straight-line tube while hypo burden is elevated.",
            )
        }
        if (hypo >= 0.055 && preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)) {
            proposeBoolean(
                out, preferences, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, false, tier,
                "Disable pragmatic relief during frequent lows to avoid aggressive SMB routing.",
            )
        }
    }

    private fun step(tier: TuningStepTier, micro: Double, moderate: Double, strong: Double): Double =
        when (tier) {
            TuningStepTier.MICRO -> micro
            TuningStepTier.MODERATE -> moderate
            TuningStepTier.STRONG -> strong
        }

    private fun proposeBoolean(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        key: BooleanKey,
        target: Boolean,
        tier: TuningStepTier,
        reason: String,
    ) {
        val current = preferences.get(key)
        if (current == target) return
        out += TuningChange(key, key.key, current, target, reason, tier)
    }

    private fun proposeDoubleIncrease(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        key: DoubleKey,
        delta: Double,
        tier: TuningStepTier,
        reason: String,
    ) {
        val current = preferences.get(key)
        val proposed = round3((current + delta).coerceIn(key.min, key.max))
        if (abs(proposed - current) < EPS) return
        if (proposed <= current + EPS) return
        out += TuningChange(key, key.key, current, proposed, reason, tier)
    }

    private fun proposeDoubleDecrease(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        key: DoubleKey,
        delta: Double,
        tier: TuningStepTier,
        reason: String,
    ) {
        val current = preferences.get(key)
        val proposed = round3((current - delta).coerceIn(key.min, key.max))
        if (abs(proposed - current) < EPS) return
        if (proposed >= current - EPS) return
        out += TuningChange(key, key.key, current, proposed, reason, tier)
    }

    /**
     * SMB tail damping proposals. The stored pref is a multiplicative FLOOR applied at high tail
     * IOB (lower value = stronger damping — see [PkpdSmbTailDamping]). Both helpers operate on the
     * effective value (legacy ≤0.55 values are first normalised to neutral) and clamp inside the
     * slider band [0.70, 0.92] so the guard is never disabled nor pushed into the legacy zone.
     */
    private fun proposeTailDampingWeaken(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        delta: Double,
        tier: TuningStepTier,
        reason: String,
    ) {
        val stored = preferences.get(DoubleKey.OApsAIMISmbTailDamping)
        val current = PkpdSmbTailDamping.effectiveStoredValue(stored)
        val proposed = round3(PkpdSmbTailDamping.clampForAdvisor(current + delta))
        if (proposed <= current + EPS) return
        out += TuningChange(DoubleKey.OApsAIMISmbTailDamping, DoubleKey.OApsAIMISmbTailDamping.key, stored, proposed, reason, tier)
    }

    private fun proposeTailDampingStrengthen(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        delta: Double,
        tier: TuningStepTier,
        reason: String,
    ) {
        val stored = preferences.get(DoubleKey.OApsAIMISmbTailDamping)
        val current = PkpdSmbTailDamping.effectiveStoredValue(stored)
        val proposed = round3(PkpdSmbTailDamping.clampForAdvisor(current - delta))
        if (proposed >= current - EPS) return
        out += TuningChange(DoubleKey.OApsAIMISmbTailDamping, DoubleKey.OApsAIMISmbTailDamping.key, stored, proposed, reason, tier)
    }

    private fun round3(value: Double): Double = (value * 1000.0).roundToInt() / 1000.0

    private fun pct(fraction: Double): Int = (fraction * 100.0).roundToInt()
}
