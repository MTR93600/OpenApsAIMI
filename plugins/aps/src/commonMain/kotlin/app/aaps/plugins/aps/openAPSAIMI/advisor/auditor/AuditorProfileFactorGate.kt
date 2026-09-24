package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.ISF.DynamicSensitivityPolicy
import app.aaps.plugins.aps.openAPSAIMI.ISF.WorkingIsf
import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoConstants
import kotlin.math.max
import kotlin.math.min

/**
 * Judges a validated proposal again on every tick, against the live state of that tick.
 *
 * The validator asked "is this answer honest?". This object asks "is it still safe, right now?", and
 * it asks it again every few minutes for as long as the proposal lives. A factor that was fine when
 * it was written is refused the moment glucose starts to fall.
 *
 * ## An unknown input refuses
 *
 * Every rule that guards against a low treats a missing number as a refusal, never as a pass. The
 * ring is empty after the plugin is rebuilt, a tick that ended early leaves no prediction, and the
 * bucketed glucose table is empty after a sensor gap — all three are exactly the moments when
 * "no reason to worry" would be the wrong reading. Only the dose-raising direction is refused this
 * way; withholding insulin stays allowed with no inputs at all.
 *
 * ## The floor always wins
 *
 * Since commit 6a6561caab the stress ISF floor is applied to the dose-facing sensitivity itself, at
 * the last step of `WorkingIsf.finalize`. The auditor factor is applied AFTER that, and [scaleIsf]
 * can never pull the value back under the floor. So when the floor is holding the sensitivity up,
 * a factor that asks for more insulin is simply absorbed and the record says `profile_floor`. The
 * protective direction is not limited by the floor, because raising the sensitivity is the same
 * thing the floor is there to do.
 */
object AuditorProfileFactorGate {

    /**
     * The ISF rules that do not need the final sensitivity: age and the live glucose state.
     *
     * It runs early in the tick so the target rule can know how much of the shared budget the ISF
     * has taken. The floor step is [applyIsfFloor], later, where the sensitivity is final.
     */
    fun evaluateIsf(
        proposal: AuditorProfileProposal?,
        keyOn: Boolean,
        tickTimestampMs: Long,
        safety: TickSafety,
    ): IsfTickDecision {
        val held = proposal ?: return IsfTickDecision.NEUTRAL
        val requested = held.isfFactor
        val ageMs = tickTimestampMs - held.contextBuiltAtMs
        if (requested == 1.0) return IsfTickDecision.NEUTRAL.copy(ageMs = ageMs)

        val refused = mutableListOf<String>()
        if (isExpired(ageMs, requested)) refused.add(AuditorProfileFactorCodes.EXPIRED)
        if (requested < 1.0) refused.addAll(raiseRefusals(safety))
        if (!keyOn) refused.add(AuditorProfileFactorCodes.SHADOW)

        val blocked = refused.any { it != AuditorProfileFactorCodes.SHADOW }
        val effective = if (blocked) 1.0 else requested
        return IsfTickDecision(
            requested = requested,
            effective = effective,
            lowerBoundMgdl = null,
            workingRawMgdl = null,
            workingAdjustedMgdl = null,
            refusedBy = refused,
            // Never true here. Many ticks end between this decision and the place the sensitivity is
            // final, and `applied` is the field the shadow study joins on, so only the apply step
            // itself may set it. See [applyIsfFloor].
            applied = false,
            ageMs = ageMs,
        )
    }

    /**
     * Puts the still-allowed factor on the final dose-facing sensitivity, under its floor.
     *
     * This is the only place that can set `applied`, because it is the only place that runs when the
     * value is really assigned.
     *
     * @param raw the sensitivity as `WorkingIsf.finalize` left it, plus the floors it must respect.
     */
    fun applyIsfFloor(decision: IsfTickDecision, raw: AuditorIsfRaw, keyOn: Boolean): IsfTickDecision {
        val lowerBound = isfLowerBound(raw)
        // A factor that asks for more insulin needs to know the profile ISF, because half of it is
        // the floor. Unknown profile means unknown floor, and an unknown floor refuses.
        if (decision.effective < 1.0 && profileRelativeFloor(raw) == null) {
            return decision.copy(
                effective = 1.0,
                lowerBoundMgdl = lowerBound,
                workingRawMgdl = raw.workingMgdl,
                workingAdjustedMgdl = raw.workingMgdl,
                refusedBy = decision.refusedBy + AuditorProfileFactorCodes.NO_PROFILE_STATIC,
                applied = false,
            )
        }
        if (decision.effective == 1.0 || !raw.workingMgdl.isFinite() || raw.workingMgdl <= 0.0) {
            return decision.copy(
                lowerBoundMgdl = lowerBound,
                workingRawMgdl = raw.workingMgdl,
                workingAdjustedMgdl = raw.workingMgdl,
                applied = false,
            )
        }
        val adjusted = scaleIsf(raw.workingMgdl, decision.effective, lowerBound)
        val effective = adjusted / raw.workingMgdl
        val refused = decision.refusedBy.toMutableList()
        if (effective != decision.effective) refused.add(AuditorProfileFactorCodes.PROFILE_FLOOR)
        return decision.copy(
            effective = effective,
            lowerBoundMgdl = lowerBound,
            workingRawMgdl = raw.workingMgdl,
            workingAdjustedMgdl = adjusted,
            refusedBy = refused,
            applied = keyOn && effective != 1.0,
        )
    }

    /**
     * The target rules, including the bound the ISF and the target share.
     *
     * @param isfEffectiveFactor the ISF factor this tick allows, 1.0 when none. With the key off it
     *   is the shadow value, so the logged target is what the key being on would really have done.
     */
    fun evaluateTarget(
        proposal: AuditorProfileProposal?,
        keyOn: Boolean,
        tickTimestampMs: Long,
        workingTargetRawMgdl: Double,
        isfEffectiveFactor: Double,
        safety: TickSafety,
    ): TargetTickDecision {
        val held = proposal ?: return TargetTickDecision.NEUTRAL.copy(targetMgdl = workingTargetRawMgdl)
        val requested = held.targetFactor
        val ageMs = tickTimestampMs - held.contextBuiltAtMs
        val errorMgdl = safety.bgMgdl - workingTargetRawMgdl
        if (requested == 1.0) {
            return TargetTickDecision.NEUTRAL.copy(
                targetMgdl = workingTargetRawMgdl,
                errorMgdl = errorMgdl,
                ageMs = ageMs,
            )
        }

        val refused = mutableListOf<String>()
        if (isExpired(ageMs, requested)) refused.add(AuditorProfileFactorCodes.EXPIRED)
        if (requested < 1.0) refused.addAll(raiseRefusals(safety))
        if (!keyOn) refused.add(AuditorProfileFactorCodes.SHADOW)
        val blocked = refused.any { it != AuditorProfileFactorCodes.SHADOW }

        if (blocked || workingTargetRawMgdl <= 0.0 || !workingTargetRawMgdl.isFinite()) {
            return TargetTickDecision(
                requested = requested,
                effective = 1.0,
                targetMgdl = workingTargetRawMgdl,
                errorMgdl = errorMgdl,
                combinedBudgetMgdl = null,
                refusedBy = refused,
                applied = false,
                ageMs = ageMs,
            )
        }

        var budget: Double? = null
        val targetMgdl: Double
        if (requested < 1.0) {
            val allowed = combinedTargetBudgetMgdl(safety.bgMgdl, workingTargetRawMgdl, isfEffectiveFactor)
            budget = allowed
            val wanted = workingTargetRawMgdl * (1.0 - requested)
            val drop = min(wanted, allowed)
            if (drop < wanted) refused.add(AuditorProfileFactorCodes.COMBINED_BOUND)
            var lowered = workingTargetRawMgdl - drop
            if (workingTargetRawMgdl < AuditorProfileFactorLimits.TARGET_MIN_MGDL) {
                // The engine is already aiming below the hard limit for its own reasons. The auditor
                // does not get to push it lower.
                lowered = workingTargetRawMgdl
                refused.add(AuditorProfileFactorCodes.TARGET_FLOOR)
            } else if (lowered < AuditorProfileFactorLimits.TARGET_MIN_MGDL) {
                lowered = AuditorProfileFactorLimits.TARGET_MIN_MGDL
                refused.add(AuditorProfileFactorCodes.TARGET_FLOOR)
            }
            targetMgdl = lowered
        } else {
            targetMgdl = min(
                workingTargetRawMgdl * requested,
                max(workingTargetRawMgdl, AuditorProfileFactorLimits.TARGET_MAX_MGDL),
            )
        }

        val effective = targetMgdl / workingTargetRawMgdl
        return TargetTickDecision(
            requested = requested,
            effective = effective,
            targetMgdl = targetMgdl,
            errorMgdl = errorMgdl,
            combinedBudgetMgdl = budget,
            refusedBy = refused,
            applied = keyOn && effective != 1.0,
            ageMs = ageMs,
        )
    }

    /**
     * How many mg/dL the target may still drop once the ISF has taken its share.
     *
     * The SMB model picks a dose close to `(bg - target) / (ISF x k)`. An ISF factor `a` and a target
     * drop `dT` together multiply that dose by `(1 + dT / e) / a`, with `e = bg - target`. We allow
     * the two of them together no more than one single 15 % change would give, `1 / 0.85`, so
     * `dT <= e x (a / 0.85 - 1)`. The ISF is applied first and has priority; the target gets what is
     * left. At or below target there is no error to work on and nothing is allowed.
     */
    fun combinedTargetBudgetMgdl(bgMgdl: Double, workingTargetMgdl: Double, isfEffectiveFactor: Double): Double {
        val error = bgMgdl - workingTargetMgdl
        if (error <= 0.0 || !error.isFinite()) return 0.0
        val room = isfEffectiveFactor / AuditorProfileFactorLimits.FACTOR_MIN - 1.0
        return max(0.0, error * room)
    }

    /**
     * Multiplies a sensitivity by a factor, without ever crossing a bound.
     *
     * A factor of exactly 1.0 returns the very same value, bit for bit, which is what makes the key
     * being off arithmetically identical to the loop of today.
     *
     * @param lowerBoundMgdl the floor the value may not be pulled under. A value that already sits
     *   below its floor is never lowered at all.
     */
    fun scaleIsf(value: Double, factor: Double, lowerBoundMgdl: Double?): Double {
        if (factor == 1.0 || !value.isFinite()) return value
        val bound = lowerBoundMgdl?.takeIf { it.isFinite() && it > 0.0 } ?: WorkingIsf.MIN_MGDL_PER_U
        return if (factor > 1.0) {
            // The ceiling may only ever hold the value back, never pull it down: a smaller
            // sensitivity means a bigger dose, and this direction exists to withhold insulin.
            max(value, min(value * factor, WorkingIsf.MAX_MGDL_PER_U))
        } else {
            max(value * factor, min(value, bound))
        }
    }

    /**
     * The target one dose formula must use, bounded against that formula's OWN target.
     *
     * The two dose sites do not always hold the same target: the SMB site reads the local working
     * target, the basal engine reads the loop target member, and the step-activity branch moves one
     * without the other. A ratio decided on one of them is not a bound on the other, so the shared
     * budget is re-computed here, on the number this site will really use. Same arithmetic as
     * [evaluateTarget], one source of truth, and it can only ever give a smaller drop than the one
     * the decision logged.
     *
     * The protective direction keeps the plain ratio: raising a target always withholds insulin.
     *
     * @param bgMgdl the glucose of this tick, for the error the budget is measured against.
     * @param isfEffectiveFactor the ISF factor of this tick, which has first claim on the budget.
     */
    fun targetForDoseSite(
        targetMgdl: Double,
        decision: TargetTickDecision,
        bgMgdl: Double,
        isfEffectiveFactor: Double,
    ): Double {
        if (!decision.applied || decision.requested == 1.0 || !targetMgdl.isFinite()) return targetMgdl
        if (decision.requested > 1.0) return scaleTargetForDose(targetMgdl, decision.effective)
        val budget = combinedTargetBudgetMgdl(bgMgdl, targetMgdl, isfEffectiveFactor)
        val wanted = targetMgdl * (1.0 - decision.requested)
        val drop = min(wanted, budget)
        if (drop <= 0.0) return targetMgdl
        val lowered = targetMgdl - drop
        return max(lowered, min(targetMgdl, AuditorProfileFactorLimits.TARGET_MIN_MGDL))
    }

    /**
     * Multiplies a target by a ratio, without crossing the hard target limits.
     *
     * Used for the protective direction and for the logged value of the decision. The dose sites go
     * through [targetForDoseSite], which bounds the drop against their own target.
     */
    fun scaleTargetForDose(targetMgdl: Double, effectiveFactor: Double): Double {
        if (effectiveFactor == 1.0 || !targetMgdl.isFinite()) return targetMgdl
        return if (effectiveFactor < 1.0) {
            max(targetMgdl * effectiveFactor, min(targetMgdl, AuditorProfileFactorLimits.TARGET_MIN_MGDL))
        } else {
            min(targetMgdl * effectiveFactor, max(targetMgdl, AuditorProfileFactorLimits.TARGET_MAX_MGDL))
        }
    }

    /**
     * The lowest sensitivity the auditor may leave behind on this tick.
     *
     * Three floors, and the highest of them wins:
     *
     * - half the profile ISF, the same profile-relative floor the commanded ISF already has. It does
     *   not depend on any key, so the promise "the ISF never goes below its usual floor" holds for
     *   every user, not only for the ones who armed the stress floor;
     * - the stress floor of this tick, when it is armed and holding;
     * - the engine's own smallest sensitivity.
     *
     * A working sensitivity that already sits under its floor is simply not lowered at all, see
     * [scaleIsf]. On this engine the working ISF is often under half the profile ISF, so the
     * dose-raising ISF channel will be inert on those ticks by design. The target channel is not.
     */
    fun isfLowerBound(raw: AuditorIsfRaw): Double {
        val profileFloor = profileRelativeFloor(raw) ?: 0.0
        val stressFloor = raw.stressFloorMgdl?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        return maxOf(WorkingIsf.MIN_MGDL_PER_U, profileFloor, stressFloor)
    }

    /** Half the profile ISF, or null when the profile ISF of this hour is unknown. */
    private fun profileRelativeFloor(raw: AuditorIsfRaw): Double? =
        raw.profileStaticMgdl
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.times(DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR)

    /**
     * A factor that asks for more insulin lives 15 minutes, one that asks for less lives 30.
     *
     * The age is counted from the audited tick, so a slow answer is born old. A negative age means
     * the proposal belongs to a tick in the future, which can only be a clock change: refuse it.
     */
    private fun isExpired(ageMs: Long, requested: Double): Boolean {
        val maxAge =
            if (requested < 1.0) AuditorProfileFactorLimits.RAISE_MAX_AGE_MS
            else AuditorProfileFactorLimits.PROTECT_MAX_AGE_MS
        return ageMs < 0L || ageMs > maxAge
    }

    /**
     * Every live reason to refuse more insulin, all of them evaluated.
     *
     * The protective direction never comes here: withholding insulin is allowed in any state.
     */
    private fun raiseRefusals(safety: TickSafety): List<String> {
        val refused = mutableListOf<String>()
        if (safety.bgMgdl < Constants.HIGH_BG_OVERRIDE_BG_MIN) {
            refused.add(AuditorProfileFactorCodes.BG_BELOW_120)
        }
        val falling = safety.deltaMgdl5m <= PredictiveHypoConstants.FAST_FALL_DELTA ||
            (safety.deltaMgdl5m < 0.0 && safety.shortAvgDeltaMgdl5m < 0.0)
        if (falling) refused.add(AuditorProfileFactorCodes.BG_FALLING)
        val minPred = safety.minPredBgMgdl
        val minPredThreshold = safety.minPredThresholdMgdl
        val predictedLow = safety.bgMgdl <= safety.hypoThresholdMgdl ||
            (minPred != null && minPredThreshold != null && minPred < minPredThreshold)
        if (predictedLow) refused.add(AuditorProfileFactorCodes.PREDICTED_LOW)
        if (minPred == null || minPredThreshold == null) {
            // No prediction to look at. That happens after the plugin is rebuilt (the ring is empty
            // while the proposal is process-global) and after a tick that ended early. It must refuse:
            // the ISF is decided once, here, and a missing prediction is not a safe prediction.
            refused.add(AuditorProfileFactorCodes.NO_PREDICTION)
        }
        val minBg75m = safety.minBg75mMgdl
        if (minBg75m == null) {
            // The glucose history cannot answer, so the post-hypo rule cannot run. Refuse.
            refused.add(AuditorProfileFactorCodes.NO_GLUCOSE_HISTORY)
        }
        if (safety.postHypoActive || (minBg75m != null && minBg75m < Constants.HYPO_GUARD_TARGET_MGDL)) {
            refused.add(AuditorProfileFactorCodes.POST_HYPO)
        }
        if (safety.exerciseLockout) refused.add(AuditorProfileFactorCodes.EXERCISE)
        if (safety.cgmNoise >= AuditorProfileFactorLimits.CGM_NOISE_UNTRUSTED) {
            refused.add(AuditorProfileFactorCodes.CGM_NOISE)
        }
        return refused
    }
}
