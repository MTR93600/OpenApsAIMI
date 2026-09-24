package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.model.Constants
import kotlin.math.abs
import kotlin.math.max

/**
 * Judges one answer of the profile checker, once, when it comes back.
 *
 * Two ideas carry the whole object:
 *
 * 1. **The claims are a transcription check, not a reasoning check.** All six numbers the model is
 *    asked to quote are printed in the prompt, so copying them is easy and passing the check proves
 *    only that the model read the data block it was given. That is worth having — it catches an
 *    answer written about a window that never happened — but it is NOT evidence that the conclusion
 *    follows from the data. Do not rely on it as the guard.
 * 2. **The real guard is the evidence list, and the two directions are not equal.** Asking for LESS
 *    insulin is always allowed once the answer itself is sound. Asking for MORE insulin must also
 *    survive [checkRaiseEvidence], a list of Kotlin rules about carbs, meals, measured sensitivity
 *    and the shape of the window.
 *
 * Every rule runs, even after one has already refused, so the record shows every reason.
 */
object AuditorProfileFactorValidator {

    /**
     * @param minConfidence the user's own minimum, `IntKey.AimiAuditorMinConfidence` / 100.
     * @param auditId the `event_id` of the audited tick.
     * @param receivedAtMs when the answer came back. Ages are measured from the tick, not from here.
     */
    fun validate(
        output: AuditorProfileFactorLlmOutput,
        context: AuditorProfileContext,
        minConfidence: Double,
        auditId: String,
        receivedAtMs: Long,
        keyOnAtArrival: Boolean,
        providerName: String,
        mainVerdictName: String,
        latencyMs: Long,
        contextJson: String,
    ): AuditorProfileProposal {
        val refused = mutableListOf<String>()
        val claimCheck = mutableListOf<ClaimCheckRow>()

        if (output.failure != null) {
            refused.add(AuditorProfileFactorCodes.LLM_ERROR_PREFIX + output.failure)
            return proposal(
                auditId, context, receivedAtMs, 1.0, 1.0, ProfileFactorDirection.NONE, output,
                refused, claimCheck, keyOnAtArrival, providerName, mainVerdictName, latencyMs, contextJson,
            )
        }

        // The parser already turned anything unreadable into 1.0. Carry its notes into the record.
        refused.addAll(output.parseIssues)

        var isf = clamp(output.isfFactorRaw)
        var target = clamp(output.targetFactorRaw)
        if (isf != output.isfFactorRaw) refused.add(AuditorProfileFactorCodes.ISF_CLAMPED)
        if (target != output.targetFactorRaw) refused.add(AuditorProfileFactorCodes.TARGET_CLAMPED)
        isf = neutralise(isf)
        target = neutralise(target)

        val raising = isf < 1.0 || target < 1.0
        val protective = isf > 1.0 || target > 1.0
        if (raising && protective) {
            refused.add(AuditorProfileFactorCodes.MIXED_DIRECTION)
            isf = 1.0
            target = 1.0
        }

        if (isf != 1.0 || target != 1.0) {
            val wanted =
                if (raising) ProfileFactorReasonCode.RESISTANCE_UNDER_CORRECTION
                else ProfileFactorReasonCode.SENSITIVITY_OVER_CORRECTION
            if (output.reasonCode != wanted) {
                refused.add(AuditorProfileFactorCodes.REASON_MISMATCH)
                isf = 1.0
                target = 1.0
            }
        }

        if (output.confidence < minConfidence) {
            refused.add(AuditorProfileFactorCodes.LOW_CONFIDENCE)
            isf = 1.0
            target = 1.0
        }

        if (!context.complete) {
            refused.add(AuditorProfileFactorCodes.CONTEXT_INCOMPLETE)
            isf = 1.0
            target = 1.0
        }

        val claimsOk = checkClaims(output.claims, context, refused, claimCheck)
        if (!claimsOk) {
            isf = 1.0
            target = 1.0
        }

        if (raising && checkRaiseEvidence(output, context, refused)) {
            isf = 1.0
            target = 1.0
        }

        val direction = when {
            isf == 1.0 && target == 1.0 -> ProfileFactorDirection.NONE
            raising                     -> ProfileFactorDirection.RAISE
            else                        -> ProfileFactorDirection.PROTECT
        }
        return proposal(
            auditId, context, receivedAtMs, isf, target, direction, output,
            refused, claimCheck, keyOnAtArrival, providerName, mainVerdictName, latencyMs, contextJson,
        )
    }

    private fun clamp(value: Double): Double =
        value.coerceIn(AuditorProfileFactorLimits.FACTOR_MIN, AuditorProfileFactorLimits.FACTOR_MAX)

    /** A factor inside the noise band is simply 1.0, so 0.995 never counts as a proposal. */
    private fun neutralise(value: Double): Double =
        if (abs(value - 1.0) < AuditorProfileFactorLimits.NEUTRAL_BAND) 1.0 else value

    /**
     * Compares the six claims with the window.
     *
     * @return true when all six exist and all six match.
     */
    private fun checkClaims(
        claims: ProfileFactorClaims,
        context: AuditorProfileContext,
        refused: MutableList<String>,
        rows: MutableList<ClaimCheckRow>,
    ): Boolean {
        var ok = true
        fun check(key: String, claimed: Double?, data: Double?, glucose: Boolean) {
            if (claimed == null || data == null) {
                refused.add(AuditorProfileFactorCodes.CLAIM_MISSING_PREFIX + key)
                rows.add(ClaimCheckRow(key, claimed, data, false))
                ok = false
                return
            }
            val tolerance =
                if (glucose) max(
                    AuditorProfileFactorLimits.CLAIM_BG_TOLERANCE_MGDL,
                    AuditorProfileFactorLimits.CLAIM_BG_TOLERANCE_FRACTION * abs(data),
                )
                else max(
                    AuditorProfileFactorLimits.CLAIM_U_TOLERANCE,
                    AuditorProfileFactorLimits.CLAIM_U_TOLERANCE_FRACTION * abs(data),
                )
            val matches = closeEnough(claimed, data, tolerance)
            if (!matches) {
                refused.add(AuditorProfileFactorCodes.CLAIM_MISMATCH_PREFIX + key)
                ok = false
            }
            rows.add(ClaimCheckRow(key, claimed, data, matches))
        }
        check("bg_start_mgdl", claims.bgStartMgdl, context.bgStartMgdl, glucose = true)
        check("bg_end_mgdl", claims.bgEndMgdl, context.bgEndMgdl, glucose = true)
        check("bg_min_mgdl", claims.bgMinMgdl, context.bgMinMgdl, glucose = true)
        check("iob_start_u", claims.iobStartU, context.iobStartU, glucose = false)
        check("iob_end_u", claims.iobEndU, context.iobEndU, glucose = false)
        check("insulin_delivered_u", claims.insulinDeliveredU, context.insulinDeliveredU, glucose = false)
        return ok
    }

    /**
     * The extra rules the dose-raising direction must survive.
     *
     * None of them is about the model's words: they all read the window Kotlin built. The model's own
     * `competingHypothesis` is the single exception, and it can only ever block, never unlock.
     *
     * ## What this cannot tell apart
     *
     * The measured sensitivity is `-(bgEnd - bgStart) / absorbed`, so any window where glucose ROSE
     * while insulin was on board reads as resistance. That is also the exact signature of a meal
     * nobody entered. The only things standing between the two are the declared ones: carbs in the
     * database, an active meal mode, COB above zero, and the meal-certainty level. When all of those
     * are silent, an undeclared meal will pass this list as resistance. The damage is bounded by the
     * 15 % per factor and by the shared bound, and the shadow study has to count this case rather
     * than assume it away.
     *
     * @return true when at least one rule refuses.
     */
    private fun checkRaiseEvidence(
        output: AuditorProfileFactorLlmOutput,
        context: AuditorProfileContext,
        refused: MutableList<String>,
    ): Boolean {
        var blocked = false
        fun refuse(code: String) {
            refused.add(code)
            blocked = true
        }
        if (context.carbsG > 0.5 || context.cobNowG > 0.5 || context.mealModeName != null) {
            refuse(AuditorProfileFactorCodes.CARBS_OR_MEAL_MODE)
        }
        if (context.mealSupport) refuse(AuditorProfileFactorCodes.MEAL_CERTAINTY)
        if (output.competingHypothesis != "none") refuse(AuditorProfileFactorCodes.COMPETING_HYPOTHESIS)

        val implied = context.impliedIsfMgdlPerU
        if (implied == null) {
            refuse(AuditorProfileFactorCodes.NOT_ENOUGH_INSULIN)
        } else {
            val engineIsf = context.engineIsfMgdl
            if (engineIsf == null || implied >= AuditorProfileFactorLimits.RESISTANCE_RATIO * engineIsf) {
                refuse(AuditorProfileFactorCodes.NO_RESISTANCE_EVIDENCE)
            }
        }

        val start = context.bgStartMgdl
        val end = context.bgEndMgdl
        if (start != null && end != null && end < start - AuditorProfileFactorLimits.BG_FALL_IN_WINDOW_MGDL) {
            refuse(AuditorProfileFactorCodes.BG_FELL_IN_WINDOW)
        }
        if (end != null && end < Constants.HIGH_BG_OVERRIDE_BG_MIN) {
            refuse(AuditorProfileFactorCodes.BG_BELOW_120)
        }
        return blocked
    }

    private fun proposal(
        auditId: String,
        context: AuditorProfileContext,
        receivedAtMs: Long,
        isf: Double,
        target: Double,
        direction: ProfileFactorDirection,
        output: AuditorProfileFactorLlmOutput,
        refused: List<String>,
        claimCheck: List<ClaimCheckRow>,
        keyOnAtArrival: Boolean,
        providerName: String,
        mainVerdictName: String,
        latencyMs: Long,
        contextJson: String,
    ): AuditorProfileProposal = AuditorProfileProposal(
        auditId = auditId,
        contextBuiltAtMs = context.contextBuiltAtMs,
        receivedAtMs = receivedAtMs,
        isfFactor = isf,
        targetFactor = target,
        direction = direction,
        reasonCode = output.reasonCode,
        confidence = output.confidence,
        refusedBy = refused.toList(),
        claimCheck = claimCheck.toList(),
        llm = output,
        keyOnAtArrival = keyOnAtArrival,
        providerName = providerName,
        mainVerdictName = mainVerdictName,
        latencyMs = latencyMs,
        contextJson = contextJson,
    )
}
