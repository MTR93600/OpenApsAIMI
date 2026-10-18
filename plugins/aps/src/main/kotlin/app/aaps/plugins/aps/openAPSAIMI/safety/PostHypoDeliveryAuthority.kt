package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import org.json.JSONObject
import java.util.Locale

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
        /**
         * An "fcl" or "anticip" note is live. Those two meal modes are not in
         * `CorrectionAggressionGate.Input.explicitMealMode` on purpose (the FCL must not buy a weaker LGS
         * interlock), so without this a meal declared with them right after a low would have its SMB
         * capped to 0 here, while the same meal declared as "lunch" would not. Read by this authority only.
         */
        val declaredMealNote: Boolean = false,
    )

    data class Decision(
        val active: Boolean,
        val forceMealInterpretationSuppressed: Boolean,
        val suppressMealDelivery: Boolean,
        val maxSmbU: Double,
        val reasonTag: String,
        /** The patient mode this decision was taken on. Exported, so a support package can check it. */
        val patientModeSeen: PatientMode? = null,
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

    /** An "fcl" or "anticip" meal is declared: a meal the person announced is not a rebound. */
    const val REASON_DECLARED_MEAL_NOTE = "inactive_declared_meal_note"

    fun evaluate(input: Input): Decision = decide(input).copy(patientModeSeen = input.patientMode)

    private fun decide(input: Input): Decision {
        if (input.gate?.tier != CorrectionAggressionGate.Tier.REBOUND_GUARD)
            return inactiveBecause(REASON_TIER_NOT_REBOUND)
        if (input.patientMode != PatientMode.POST_HYPO_RECOVERY)
            return inactiveBecause(REASON_MODE_NOT_POST_HYPO)
        if (CorrectionAggressionGate.hasIndependentMealEvidence(input.aggressionInput))
            return inactiveBecause(REASON_MEAL_EVIDENCE)
        if (input.declaredMealNote) return inactiveBecause(REASON_DECLARED_MEAL_NOTE)
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
                reasonTag = PostHypoAggressiveRiseExit.REASON_CODE.lowercase(Locale.US),
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
                    String.format(Locale.US, "%.2f", decision.maxSmbU)
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
    ): JSONObject = JSONObject().apply {
        put("active", decision.active)
        put("reason_tag", decision.reasonTag)
        put("force_meal_interpretation_suppressed", decision.forceMealInterpretationSuppressed)
        put("suppress_meal_delivery", decision.suppressMealDelivery)
        put("max_smb_u", if (decision.maxSmbU.isFinite()) decision.maxSmbU else JSONObject.NULL)
        put("smb_before_cap_u", smbBeforeCapU ?: JSONObject.NULL)
        put("smb_after_cap_u", smbAfterCapU ?: JSONObject.NULL)
        put("patient_mode_seen", decision.patientModeSeen?.name ?: JSONObject.NULL)
    }
}
