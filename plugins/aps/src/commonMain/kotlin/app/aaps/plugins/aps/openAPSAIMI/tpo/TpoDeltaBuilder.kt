package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningChange
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlin.math.abs

internal object TpoDeltaBuilder {

    private const val EPS = 0.0001

    fun buildPlan(
        proposal: TpoProposal,
        preferences: Preferences,
        hypoLoad: Double,
        t3cBrittle: Boolean,
    ): TpoApplyPlan {
        val changes = when (proposal.packId) {
            TpoPackId.POST_HYPO_RECOVERY -> buildPostHypoChanges(
                preferences = preferences,
                tier = proposal.tier,
                hypoLoad = hypoLoad,
                t3cBrittle = t3cBrittle,
            )
            TpoPackId.POOR_SLEEP_WINDOW -> buildPoorSleepChanges(
                preferences = preferences,
                tier = proposal.tier,
                t3cBrittle = t3cBrittle,
            )
            TpoPackId.EXHAUSTED_RECOVERY -> buildExhaustedChanges(
                preferences = preferences,
                tier = proposal.tier,
                t3cBrittle = t3cBrittle,
            )
        }
        return TpoApplyPlan(proposal = proposal, changes = changes)
    }

    private fun buildPostHypoChanges(
        preferences: Preferences,
        tier: TuningStepTier,
        hypoLoad: Double,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val protectionSteps = when (tier) {
            TuningStepTier.MICRO -> 1
            TuningStepTier.MODERATE -> 1
            TuningStepTier.STRONG -> 2
        }
        val tailSteps = when (tier) {
            TuningStepTier.MICRO -> 1
            TuningStepTier.MODERATE -> 1
            TuningStepTier.STRONG -> 2
        }
        val out = mutableListOf<TuningChange>()
        appendProtectionDown(out, preferences, protectionSteps, tier, "TPO post-hypo protection")
        if (!t3cBrittle) {
            appendTailStrengthen(out, preferences, tailSteps, tier)
        }
        if (tier == TuningStepTier.STRONG || hypoLoad >= 0.35) {
            appendMealFactorDown(out, preferences, steps = 1, tier)
            appendBooleanIfTrue(out, preferences, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, false, tier)
            appendBooleanIfTrue(out, preferences, BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled, false, tier)
            appendTubeProtection(out, preferences, aggressivenessFactor = 0.85, hypoFloorSteps = 1, tier)
        }
        return out
    }

    private fun buildPoorSleepChanges(
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val protectionSteps = when (tier) {
            TuningStepTier.MICRO -> 1
            TuningStepTier.MODERATE -> 1
            TuningStepTier.STRONG -> 1
        }
        val out = mutableListOf<TuningChange>()
        appendProtectionDown(
            out,
            preferences,
            protectionSteps,
            tier,
            "TPO poor-sleep protection",
            includePriority = tier != TuningStepTier.MICRO,
        )
        if (!t3cBrittle) {
            appendTailStrengthen(out, preferences, steps = 1, tier)
            if (tier == TuningStepTier.STRONG) {
                appendDoubleChange(
                    out,
                    preferences,
                    DoubleKey.OApsAIMISmbExerciseDamping,
                    TpoLadderSupport.strengthenExerciseDamping(preferences, 1),
                    tier,
                    "TPO poor-sleep exercise damping",
                )
                appendDoubleChange(
                    out,
                    preferences,
                    DoubleKey.OApsAIMISmbLateFatDamping,
                    TpoLadderSupport.strengthenLateFatDamping(preferences, 1),
                    tier,
                    "TPO poor-sleep late-fat damping",
                )
            }
        }
        return out
    }

    private fun buildExhaustedChanges(
        preferences: Preferences,
        tier: TuningStepTier,
        t3cBrittle: Boolean,
    ): List<TuningChange> {
        val protectionSteps = when (tier) {
            TuningStepTier.MICRO -> 1
            TuningStepTier.MODERATE -> 2
            TuningStepTier.STRONG -> 2
        }
        val out = mutableListOf<TuningChange>()
        appendProtectionDown(out, preferences, protectionSteps, tier, "TPO exhausted protection")
        if (!t3cBrittle) {
            appendTailStrengthen(out, preferences, steps = 2, tier)
        }
        appendMealFactorDown(out, preferences, steps = 1, tier)
        appendBooleanIfTrue(out, preferences, BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled, false, tier)
        appendBooleanIfTrue(out, preferences, BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled, false, tier)
        appendTubeProtection(out, preferences, aggressivenessFactor = 0.72, hypoFloorSteps = 2, tier)
        return out
    }

    private fun appendProtectionDown(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        steps: Int,
        tier: TuningStepTier,
        reason: String,
        includePriority: Boolean = true,
    ) {
        appendDoubleChange(
            out, preferences, DoubleKey.OApsAIMIMaxSMB,
            TpoLadderSupport.stepDownLadder(preferences, DoubleKey.OApsAIMIMaxSMB, TpoLadderSupport.MAX_SMB_LADDER, steps),
            tier, reason,
        )
        appendDoubleChange(
            out, preferences, DoubleKey.OApsAIMIHighBGMaxSMB,
            TpoLadderSupport.stepDownLadder(
                preferences,
                DoubleKey.OApsAIMIHighBGMaxSMB,
                TpoLadderSupport.HIGH_BG_MAX_SMB_LADDER,
                steps,
            ),
            tier, reason,
        )
        if (includePriority) {
            appendDoubleChange(
                out, preferences, DoubleKey.OApsAIMIPriorityMaxIobFactor,
                TpoLadderSupport.stepDownLadder(
                    preferences,
                    DoubleKey.OApsAIMIPriorityMaxIobFactor,
                    TpoLadderSupport.PRIORITY_MAX_IOB_FACTOR_LADDER,
                    steps,
                ),
                tier, reason,
            )
            appendDoubleChange(
                out, preferences, DoubleKey.OApsAIMIPriorityMaxIobExtraU,
                TpoLadderSupport.stepDownLadder(
                    preferences,
                    DoubleKey.OApsAIMIPriorityMaxIobExtraU,
                    TpoLadderSupport.PRIORITY_MAX_IOB_EXTRA_LADDER,
                    steps,
                ),
                tier, reason,
            )
            appendDoubleChange(
                out, preferences, DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor,
                TpoLadderSupport.stepDownLadder(
                    preferences,
                    DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor,
                    TpoLadderSupport.PKPD_RELIEF_MIN_LADDER,
                    steps,
                ),
                tier, reason,
            )
            appendDoubleChange(
                out, preferences, DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
                TpoLadderSupport.stepDownLadder(
                    preferences,
                    DoubleKey.OApsAIMIRedCarpetRestoreThreshold,
                    TpoLadderSupport.RED_CARPET_RESTORE_LADDER,
                    steps,
                ),
                tier, reason,
            )
        }
    }

    private fun appendTailStrengthen(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        steps: Int,
        tier: TuningStepTier,
    ) {
        appendDoubleChange(
            out,
            preferences,
            DoubleKey.OApsAIMISmbTailDamping,
            TpoLadderSupport.strengthenTailDamping(preferences, steps),
            tier,
            "TPO strengthen SMB tail damping",
        )
    }

    private fun appendMealFactorDown(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        steps: Int,
        tier: TuningStepTier,
    ) {
        appendDoubleChange(
            out, preferences, DoubleKey.OApsAIMILunchFactor,
            TpoLadderSupport.stepDownLadder(
                preferences,
                DoubleKey.OApsAIMILunchFactor,
                TpoLadderSupport.MEAL_FACTOR_LADDER,
                steps,
            ),
            tier,
            "TPO lower lunch factor during recovery",
        )
        appendDoubleChange(
            out, preferences, DoubleKey.OApsAIMIDinnerFactor,
            TpoLadderSupport.stepDownLadder(
                preferences,
                DoubleKey.OApsAIMIDinnerFactor,
                TpoLadderSupport.MEAL_FACTOR_LADDER,
                steps,
            ),
            tier,
            "TPO lower dinner factor during recovery",
        )
    }

    private fun appendTubeProtection(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        aggressivenessFactor: Double,
        hypoFloorSteps: Int,
        tier: TuningStepTier,
    ) {
        if (!preferences.get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)) return
        appendDoubleChange(
            out,
            preferences,
            DoubleKey.AimiTubeAggressiveness,
            TpoLadderSupport.scaleTubeAggressiveness(preferences, aggressivenessFactor),
            tier,
            "TPO reduce tube aggressiveness",
        )
        appendDoubleChange(
            out,
            preferences,
            DoubleKey.AimiTubeHypoFloorMgdl,
            TpoLadderSupport.stepUpLadder(
                preferences,
                DoubleKey.AimiTubeHypoFloorMgdl,
                TpoLadderSupport.TUBE_HYPO_FLOOR_LADDER,
                hypoFloorSteps,
            ),
            tier,
            "TPO raise tube hypo floor",
        )
    }

    private fun appendBooleanIfTrue(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        key: BooleanKey,
        target: Boolean,
        tier: TuningStepTier,
    ) {
        val current = preferences.get(key)
        if (current == target) return
        out += TuningChange(key, key.key, current, target, "TPO safety disable", tier)
    }

    private fun appendDoubleChange(
        out: MutableList<TuningChange>,
        preferences: Preferences,
        key: DoubleKey,
        target: Double?,
        tier: TuningStepTier,
        reason: String,
    ) {
        if (target == null) return
        val current = preferences.get(key)
        if (abs(current - target) < EPS) return
        out += TuningChange(key, key.key, current, target, reason, tier)
    }
}
