package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mirror of the basal-first safety verdict, as Harmonia would see it **if it were told**.
 *
 * Harmonia is asked for a plan long before the tick knows whether the SMB channel was zeroed for
 * safety. The verdict is posted later, and the guard reads it later still. Harmonia therefore never
 * learns the rule it is judged on, and never learns that it was refused.
 *
 * This class is that missing message, written down once so it can be measured. It is
 * **strictly passive**: nothing in the dosing chain reads it, and the field
 * [wouldBlockBasalFirst] is only a copy of the real rule, never the rule itself.
 *
 * The copy must stay identical to
 * [app.aaps.plugins.aps.openAPSAIMI.basal.BasalChannelSafetyGuards.shouldBlockBasalFirst].
 * `HarmoniaCounterfactualTest.verdictMirrorsBasalChannelSafetyGuards` compares the two call for
 * call over the full truth table, so a change on one side and not the other fails the build.
 *
 * Source: `origin/dev_OAPSAIMI` @ `da9bc789ce` (file SHA `f89412ab72`, unchanged on tip
 * `c5db5a0333`). Study wiring: commonMain `internal object` (pure, no instance state, one tick
 * consumer). KMP adaptation only: kotlinx JSON instead of `org.json`, [aimiFmt2] instead of
 * `String.format(Locale.US)`. Clinical formulas are unchanged.
 *
 * @param criticalSafetyZeroed a safety rule zeroed the SMB of this tick.
 * @param contextSuppressSmb the active context suppresses the SMB of this tick.
 * @param mealModeActive a manual meal mode is declared, which exempts the channel from the guard.
 * @param guardsEnabled the guard preference is on. When off, the guard blocks nothing.
 */
data class HarmoniaSafetyVerdict(
    val criticalSafetyZeroed: Boolean,
    val contextSuppressSmb: Boolean,
    val mealModeActive: Boolean,
    val guardsEnabled: Boolean,
) {

    /** Exact copy of the production rule. Read the class doc before touching it. */
    val wouldBlockBasalFirst: Boolean =
        guardsEnabled && !mealModeActive && (criticalSafetyZeroed || contextSuppressSmb)

    companion object {

        /** No verdict was gathered for this tick. Blocks nothing, judges nothing. */
        val UNKNOWN = HarmoniaSafetyVerdict(
            criticalSafetyZeroed = false,
            contextSuppressSmb = false,
            mealModeActive = false,
            guardsEnabled = false,
        )
    }
}

/**
 * Which of the five cases the tick fell into. One case per line, no overlap.
 */
enum class HarmoniaCounterfactualRule {

    /** No refusal to explain, or no request to compare. The counterfactual is the identity. */
    NO_VERDICT,

    /**
     * The safety guard refused a request that was **below** the profile basal. The guard exists to
     * stop over-dosing, so refusing an under-dose adds insulin instead of removing it. Knowing the
     * verdict would not change the proposal: Harmonia was already standing down.
     */
    BLOCKED_REDUCTION,

    /** The safety guard refused a request that was, in practice, the profile basal. Nothing at stake. */
    BLOCKED_NEUTRAL,

    /**
     * The safety guard refused a request **above** the profile basal. This is the only case where
     * telling Harmonia would change the proposal: it would stand down to the profile by itself.
     */
    WOULD_STAND_DOWN,

    /** Some other blocker refused the tick. It must not be charged to the safety guard. */
    BLOCKED_NON_SAFETY,
}

/**
 * What Harmonia would have proposed if the safety verdict had been part of its environment, and
 * what the refusal cost in insulin.
 *
 * Every field is an observation. Nothing here is applied, and nothing in the dosing chain reads it.
 *
 * @param rule which case the tick fell into.
 * @param actualAction the action Harmonia really proposed.
 * @param counterfactualAction the action it would propose knowing the verdict.
 * @param counterfactualBasalUph the basal rate that goes with [counterfactualAction], U/h.
 * @param changesProposal `true` only when the two proposals differ. This is the measured answer to
 *   "would telling Harmonia change anything".
 * @param requestWasEscalation the request was above the profile basal plus one pump step.
 * @param requestDeltaVsProfileUph request minus profile basal, U/h, signed.
 * @param blockStakeU insulin the refusal moved over the applied duration, U, signed. Negative means
 *   the refusal **added** insulin, because it refused an under-dose.
 * @param appliedGapUph applied rate minus requested rate, U/h. How far the pump ended up from the
 *   request, whatever the reason.
 * @param reason one short line, for a human reading the export.
 */
data class HarmoniaCounterfactualOutcome(
    val rule: HarmoniaCounterfactualRule,
    val actualAction: HarmoniaAction?,
    val counterfactualAction: HarmoniaAction?,
    val counterfactualBasalUph: Double?,
    val changesProposal: Boolean,
    val requestWasEscalation: Boolean,
    val requestDeltaVsProfileUph: Double?,
    val blockStakeU: Double?,
    val appliedGapUph: Double?,
    val reason: String,
) {

    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("rule", rule.name)
            put("actual_action", actualAction?.name)
            put("counterfactual_action", counterfactualAction?.name)
            put("counterfactual_basal_uph", counterfactualBasalUph)
            put("changes_proposal", changesProposal)
            put("request_was_escalation", requestWasEscalation)
            put("request_delta_vs_profile_uph", requestDeltaVsProfileUph)
            put("block_stake_u", blockStakeU)
            put("applied_gap_uph", appliedGapUph)
            put("reason", reason)
            put("source", "harmonia_counterfactual_v1")
        }
}

/**
 * Answers two questions from data the tick already holds, and answers nothing else:
 *
 *  1. would Harmonia propose something else if it were told the safety verdict,
 *  2. how much insulin does the refusal move.
 *
 * ## Strictly passive
 *
 * Nothing in the dosing chain may read this. To check that, run:
 *
 * ```
 * grep -rn "HarmoniaCounterfactual\|harmonia_cf_" plugins/aps/src/commonMain plugins/aps/src/androidMain
 * ```
 *
 * It must return exactly three zones and nothing else: this file, the `BaselineState` field
 * declarations with their `put` lines, and the one feeding block in the export stage of
 * `DetermineBasalAIMI2`. Any other line is a violation.
 */
internal object HarmoniaCounterfactual {

    /**
     * Half-band around the profile basal, in U/h. One pump step: a difference smaller than this
     * cannot be delivered, so it is not a real request to change the rate.
     */
    const val NEUTRAL_BAND_UPH = 0.05

    /** The runtime blocker string the basal-first safety guard writes. */
    const val SAFETY_BLOCKER = "smb_zeroed_by_safety"

    /**
     * @param simulation what Harmonia proposed this tick, `null` when it was not asked.
     * @param production the production branch record, which carries the runtime blocker.
     * @param verdict the safety verdict, mirrored. Pass [HarmoniaSafetyVerdict.UNKNOWN] when it
     *   could not be gathered.
     * @param profileBasalUph the profile basal of the tick, U/h.
     * @param appliedRateUph the rate the tick really asks the pump for, U/h.
     * @param appliedDurationMin the duration that rate is asked for, minutes.
     */
    fun evaluate(
        simulation: HarmoniaDecision?,
        production: HarmoniaProductionDecision?,
        verdict: HarmoniaSafetyVerdict,
        profileBasalUph: Double,
        appliedRateUph: Double?,
        appliedDurationMin: Int,
    ): HarmoniaCounterfactualOutcome {
        val actualAction = production?.sourceAction ?: simulation?.action
        val requestedRateUph = production?.requestedRateUph ?: simulation?.targetBasalUph
        val blocker = production?.runtimeBlocker

        if (requestedRateUph == null || !requestedRateUph.isFinite() || !profileBasalUph.isFinite()) {
            return identity(
                rule = HarmoniaCounterfactualRule.NO_VERDICT,
                actualAction = actualAction,
                requestedRateUph = requestedRateUph,
                requestDeltaVsProfileUph = null,
                requestWasEscalation = false,
                blockStakeU = null,
                appliedGapUph = null,
                reason = "no comparable request this tick",
            )
        }

        val delta = requestedRateUph - profileBasalUph
        val escalation = delta > NEUTRAL_BAND_UPH
        val stake = delta * appliedDurationMin / 60.0
        val gap = appliedRateUph?.takeIf { it.isFinite() }?.let { it - requestedRateUph }

        if (blocker == null) {
            return identity(
                rule = HarmoniaCounterfactualRule.NO_VERDICT,
                actualAction = actualAction,
                requestedRateUph = requestedRateUph,
                requestDeltaVsProfileUph = delta,
                requestWasEscalation = escalation,
                blockStakeU = 0.0,
                appliedGapUph = gap,
                reason = "no refusal this tick",
            )
        }

        if (blocker != SAFETY_BLOCKER) {
            return identity(
                rule = HarmoniaCounterfactualRule.BLOCKED_NON_SAFETY,
                actualAction = actualAction,
                requestedRateUph = requestedRateUph,
                requestDeltaVsProfileUph = delta,
                requestWasEscalation = escalation,
                blockStakeU = stake,
                appliedGapUph = gap,
                reason = "refused by $blocker, not by the basal safety guard",
            )
        }

        if (!verdict.wouldBlockBasalFirst) {
            return identity(
                rule = HarmoniaCounterfactualRule.NO_VERDICT,
                actualAction = actualAction,
                requestedRateUph = requestedRateUph,
                requestDeltaVsProfileUph = delta,
                requestWasEscalation = escalation,
                blockStakeU = stake,
                appliedGapUph = gap,
                reason = "safety blocker seen, but the mirrored verdict is not active",
            )
        }

        return when {
            delta < -NEUTRAL_BAND_UPH -> identity(
                rule = HarmoniaCounterfactualRule.BLOCKED_REDUCTION,
                actualAction = actualAction,
                requestedRateUph = requestedRateUph,
                requestDeltaVsProfileUph = delta,
                requestWasEscalation = false,
                blockStakeU = stake,
                appliedGapUph = gap,
                reason = "guard refused a request ${aimiFmt2(-delta)} U/h below profile; knowing the verdict changes nothing",
            )

            delta <= NEUTRAL_BAND_UPH -> identity(
                rule = HarmoniaCounterfactualRule.BLOCKED_NEUTRAL,
                actualAction = actualAction,
                requestedRateUph = requestedRateUph,
                requestDeltaVsProfileUph = delta,
                requestWasEscalation = false,
                // Inside one pump step the difference cannot be delivered, so nothing is at stake.
                blockStakeU = 0.0,
                appliedGapUph = gap,
                reason = "guard refused a request equal to the profile basal within one pump step",
            )

            else -> HarmoniaCounterfactualOutcome(
                rule = HarmoniaCounterfactualRule.WOULD_STAND_DOWN,
                actualAction = actualAction,
                counterfactualAction = HarmoniaAction.OBSERVE,
                counterfactualBasalUph = profileBasalUph,
                changesProposal = true,
                requestWasEscalation = true,
                requestDeltaVsProfileUph = delta,
                blockStakeU = stake,
                appliedGapUph = gap,
                reason = "guard refused a request ${aimiFmt2(delta)} U/h above profile; knowing the verdict, Harmonia would stand down",
            )
        }
    }

    /** The counterfactual proposal equals the real one: the outcome only carries the measurement. */
    private fun identity(
        rule: HarmoniaCounterfactualRule,
        actualAction: HarmoniaAction?,
        requestedRateUph: Double?,
        requestDeltaVsProfileUph: Double?,
        requestWasEscalation: Boolean,
        blockStakeU: Double?,
        appliedGapUph: Double?,
        reason: String,
    ): HarmoniaCounterfactualOutcome =
        HarmoniaCounterfactualOutcome(
            rule = rule,
            actualAction = actualAction,
            counterfactualAction = actualAction,
            counterfactualBasalUph = requestedRateUph,
            changesProposal = false,
            requestWasEscalation = requestWasEscalation,
            requestDeltaVsProfileUph = requestDeltaVsProfileUph,
            blockStakeU = blockStakeU,
            appliedGapUph = appliedGapUph,
            reason = reason,
        )
}
