package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode

/**
 * Transversal authority when post-hypo rebound guard is active: arbitrates basal (via
 * [CorrectionAggressionBasalCap]), meal interpretation uplift, and SMB delivery.
 *
 * Scoped to [CorrectionAggressionGate.Tier.REBOUND_GUARD] + [PatientMode.POST_HYPO_RECOVERY]
 * without independent meal evidence — real meals escape via gate bg exit or COB/mode.
 */
object PostHypoDeliveryAuthority {

    const val LOG_PREFIX = "POST_HYPO_DELIVERY"

    data class Input(
        val gate: CorrectionAggressionGate.Decision?,
        val patientMode: PatientMode?,
        val aggressionInput: CorrectionAggressionGate.Input,
    )

    data class Decision(
        val active: Boolean,
        val forceMealInterpretationSuppressed: Boolean,
        val suppressMealDelivery: Boolean,
        val maxSmbU: Double,
        val reasonTag: String,
    ) {
        fun capSmbU(requestedU: Double): Double =
            if (!active || !suppressMealDelivery) requestedU else minOf(requestedU, maxSmbU)
    }

    val INACTIVE = Decision(
        active = false,
        forceMealInterpretationSuppressed = false,
        suppressMealDelivery = false,
        maxSmbU = Double.POSITIVE_INFINITY,
        reasonTag = "inactive",
    )

    /**
     * Same shape as [INACTIVE] but naming the condition that declined the authority, so a support
     * package says *why* the guard did not apply instead of only that it did not.
     * See `docs/adr/0006-autodrive-consumes-authority.md`.
     */
    private fun inactiveBecause(reason: String): Decision = INACTIVE.copy(reasonTag = reason)

    /** Tick is not classified as a post-hypo rebound by [CorrectionAggressionGate]. */
    const val REASON_TIER_NOT_REBOUND = "inactive_tier_not_rebound"

    /** Patient mode is not post-hypo recovery. */
    const val REASON_MODE_NOT_POST_HYPO = "inactive_mode_not_post_hypo"

    /** Independent meal evidence (COB, declared mode, carb estimate, confirmed rise) was found. */
    const val REASON_MEAL_EVIDENCE = "inactive_meal_evidence"

    fun evaluate(input: Input): Decision {
        if (input.gate?.tier != CorrectionAggressionGate.Tier.REBOUND_GUARD)
            return inactiveBecause(REASON_TIER_NOT_REBOUND)
        if (input.patientMode != PatientMode.POST_HYPO_RECOVERY)
            return inactiveBecause(REASON_MODE_NOT_POST_HYPO)
        if (CorrectionAggressionGate.hasIndependentMealEvidence(input.aggressionInput))
            return inactiveBecause(REASON_MEAL_EVIDENCE)
        // Aggressive rise past target+30 with Δ>15 → act normally (product 2026-07-17).
        if (
            PostHypoAggressiveRiseExit.shouldExit(
                bgMgdl = input.aggressionInput.bg,
                targetBgMgdl = input.aggressionInput.targetBg,
                deltaMgdl5m = input.aggressionInput.deltaMgdl5m,
            )
        ) {
            return Decision(
                active = false,
                forceMealInterpretationSuppressed = false,
                suppressMealDelivery = false,
                maxSmbU = Double.POSITIVE_INFINITY,
                reasonTag = PostHypoAggressiveRiseExit.REASON_CODE.lowercase(),
            )
        }
        return Decision(
            active = true,
            forceMealInterpretationSuppressed = true,
            suppressMealDelivery = true,
            maxSmbU = 0.0,
            reasonTag = "rebound_guard_post_hypo_arbitration",
        )
    }

    fun formatLogLine(decision: Decision): String =
        buildString {
            append(LOG_PREFIX)
            append(": active=").append(decision.active)
            append(" mealSupp=").append(decision.forceMealInterpretationSuppressed)
            append(" smbCap=").append(
                if (decision.maxSmbU.isFinite()) {
                    aimiFmt2(decision.maxSmbU)
                } else {
                    "none"
                },
            )
            append(" tag=").append(decision.reasonTag)
        }

    /**
     * Snapshot for `AIMI_Decisions.jsonl` (`adjustments.post_hypo_delivery`). Diagnostic only.
     *
     * @param smbBeforeCapU SMB the channel proposed before [Decision.capSmbU] was applied.
     * @param smbAfterCapU  SMB left after the cap, so the effect of the guard is directly readable.
     */
    fun toJsonObject(
        decision: Decision,
        smbBeforeCapU: Double?,
        smbAfterCapU: Double?,
    ): JsonObject = buildJsonObject {
        put("active", decision.active)
        put("reason_tag", decision.reasonTag)
        put("force_meal_interpretation_suppressed", decision.forceMealInterpretationSuppressed)
        put("suppress_meal_delivery", decision.suppressMealDelivery)
        if (decision.maxSmbU.isFinite()) {
            put("max_smb_u", decision.maxSmbU)
        } else {
            put("max_smb_u", JsonNull)
        }
        if (smbBeforeCapU != null) {
            put("smb_before_cap_u", smbBeforeCapU)
        } else {
            put("smb_before_cap_u", JsonNull)
        }
        if (smbAfterCapU != null) {
            put("smb_after_cap_u", smbAfterCapU)
        } else {
            put("smb_after_cap_u", JsonNull)
        }
    }
}
