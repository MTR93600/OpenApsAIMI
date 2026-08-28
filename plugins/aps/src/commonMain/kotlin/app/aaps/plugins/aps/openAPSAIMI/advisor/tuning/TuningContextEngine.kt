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

    fun computePlan(
        requestedContext: AimiTuningContext,
        metrics: AdvisorMetrics,
        preferences: Preferences,
        t3cBrittleMode: Boolean,
    ): TuningPlan {
        val effective = when (requestedContext) {
            AimiTuningContext.AUTO_BALANCE -> resolveAutoContext(metrics)
            else -> requestedContext
        }
        val tier = dominantTier(effective, metrics)
        val blocked = guardBlock(effective, metrics)
        if (blocked != null) {
            return TuningPlan(requestedContext, effective, tier, emptyList(), blocked)
        }
        val changes = when (effective) {
            AimiTuningContext.MEAL_RISE -> buildMealRiseChanges(metrics, preferences, tier, t3cBrittleMode)
            AimiTuningContext.HYPO_GUARD -> buildHypoGuardChanges(metrics, preferences, tier, t3cBrittleMode)
            AimiTuningContext.HYPER_STABLE -> buildHyperStableChanges(metrics, preferences, tier, t3cBrittleMode)
            AimiTuningContext.MIXED_BALANCE -> buildMixedBalanceChanges(metrics, preferences, t3cBrittleMode)
            AimiTuningContext.AUTO_BALANCE -> emptyList()
        }
        return TuningPlan(requestedContext, effective, tier, changes)
    }

    fun resolveAutoContext(metrics: AdvisorMetrics): AimiTuningContext {
        val hypo = metrics.timeBelow70
        val hyper = metrics.timeAbove180
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

    private fun dominantTier(context: AimiTuningContext, metrics: AdvisorMetrics): TuningStepTier =
        when (context) {
            AimiTuningContext.HYPO_GUARD -> hypoTier(metrics.timeBelow70)
            AimiTuningContext.MEAL_RISE,
            AimiTuningContext.HYPER_STABLE,
            -> hyperTier(metrics.timeAbove180)
            AimiTuningContext.MIXED_BALANCE -> maxTier(
                hypoTier(metrics.timeBelow70),
                hyperTier(metrics.timeAbove180),
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

    private fun guardBlock(context: AimiTuningContext, metrics: AdvisorMetrics): String? {
        when (context) {
            AimiTuningContext.MEAL_RISE -> {
                if (metrics.timeBelow70 > 0.06) {
                    return "Meal-rise tuning blocked: hypo burden (${pct(metrics.timeBelow70)}% below 70) is too high."
                }
                if (metrics.timeAbove180 < 0.12) {
                    return "Meal-rise tuning blocked: hyper burden (${pct(metrics.timeAbove180)}% above 180) is too low."
                }
            }
            AimiTuningContext.HYPO_GUARD -> {
                if (metrics.timeBelow70 < 0.025) {
                    return "Hypo guard blocked: time below 70 (${pct(metrics.timeBelow70)}%) is already low."
                }
            }
            AimiTuningContext.HYPER_STABLE -> {
                if (metrics.timeBelow70 >= 0.045) {
                    return "Hyper tuning blocked: hypo burden (${pct(metrics.timeBelow70)}% below 70) — use Hypo guard or Auto."
                }
                if (metrics.timeAbove180 < 0.10 && metrics.tir70_180 >= 0.72) {
                    return "Hyper tuning blocked: control is already stable (TIR ${pct(metrics.tir70_180)}%)."
                }
            }
            AimiTuningContext.MIXED_BALANCE -> {
                if (metrics.timeBelow70 < 0.035 && metrics.timeAbove180 < 0.10) {
                    return "Mixed tuning blocked: neither hypo nor hyper burden is significant enough."
                }
            }
            AimiTuningContext.AUTO_BALANCE -> Unit
        }
        return null
    }

    private fun buildMealRiseChanges(
        metrics: AdvisorMetrics,
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        // Complements Hyper Trajectory Release (HTR): when OApsAIMIHyperTrajectoryRelease is on with Autodrive V3,
        // prefer HTR SMB floors over large MaxSMB jumps — avoid double-counting meal-rise aggression.
        val cappedTier = if (metrics.timeBelow70 >= 0.04) {
            capTier(tier, TuningStepTier.MICRO)
        } else {
            tier
        }
        val moderateHypoRisk = metrics.timeBelow70 >= 0.04
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
            appendMealFactorReductions(out, preferences, hypoTier(metrics.timeBelow70))
        }

        if (!moderateHypoRisk && metrics.timeAbove180 >= 0.18 && metrics.timeBelow70 < 0.04) {
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
        metrics: AdvisorMetrics,
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val out = mutableListOf<TuningChange>()
        appendHypoAggressionReductions(out, preferences, tier, t3cBrittle)
        if (metrics.timeBelow70 >= 0.04) {
            appendMealFactorReductions(out, preferences, tier)
        }
        appendHypoStrongSafetyDisables(out, preferences, metrics, tier)
        return out
    }

    private fun buildMixedBalanceChanges(
        metrics: AdvisorMetrics,
        preferences: Preferences,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val hypoT = hypoTier(metrics.timeBelow70)
        val hyperT = hyperTier(metrics.timeAbove180)
        val out = mutableListOf<TuningChange>()

        appendHypoAggressionReductions(out, preferences, hypoT, t3cBrittle)
        if (metrics.timeBelow70 >= 0.04) {
            appendMealFactorReductions(out, preferences, hypoT)
        }
        appendHypoStrongSafetyDisables(out, preferences, metrics, hypoT)

        // Hyper side: non-conflicting keys only; never raise SMB caps when hypos are significant.
        if (metrics.timeAbove180 >= 0.14 && hypoT != TuningStepTier.STRONG) {
            proposeBoolean(
                out, preferences, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, true, hyperT,
                "Enable pragmatic relief for post-meal routing without raising SMB caps (mixed pattern).",
            )
            if (metrics.timeBelow70 < 0.05) {
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
        metrics: AdvisorMetrics,
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
        if (!t3cBrittle && metrics.timeBelow70 < 0.035) {
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
        metrics: AdvisorMetrics,
        tier: TuningStepTier,
    ) {
        if (metrics.timeBelow70 >= 0.045 && preferences.get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)) {
            proposeBoolean(
                out, preferences, BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled, false, tier,
                "Disable straight-line tube while hypo burden is elevated.",
            )
        }
        if (metrics.timeBelow70 >= 0.055 && preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)) {
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
