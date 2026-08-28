package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import kotlin.math.max
import kotlin.math.min

/**
 * Explicit SMB arbitration modes for Harmonia after catalog proposals + ML demand.
 * Auditor may CONFIRM/SOFTEN only — never LIFT.
 */
enum class HarmoniaSmbAuthorityMode {
    ACCEPT,
    LIFT_WITHIN_ENVELOPE,
    REDUCE,
}

/**
 * Tree-deployed insulin intent consumed by Harmonia SMB arbitration.
 */
enum class InsulinIntent {
    NONE,
    STABILIZE,
    MEAL_SUPPORT,
    NEED_MORE_INSULIN,
    PROTECTIVE,
}

/**
 * Writes a number only when it is finite, and writes null otherwise.
 *
 * `org.json` throws on NaN and on Infinity. The decision export runs on the main route, where the
 * call is not wrapped in a catch, so one bad number would end the tick after the dose was already
 * decided. A missing field is much cheaper than a lost tick.
 */
internal fun JsonObjectBuilder.putFiniteOrNull(name: String, value: Double?) {
    if (value != null && value.isFinite()) {
        put(name, value)
    } else {
        put(name, JsonNull)
    }
}

/**
 * Single SMB authority decision for one tick — produced before finalizeAndCapSMB.
 */
data class HarmoniaSmbAuthorityDecision(
    val mode: HarmoniaSmbAuthorityMode,
    val smbU: Double,
    val demandBeforeU: Double,
    val mpcDemandU: Double,
    val catalogProposedCapU: Double?,
    val catalogCapKind: PatternCapKind?,
    val envelopeMaxU: Double,
    val insulinIntent: InsulinIntent,
    val reasons: List<String>,
    val addsSmbAuthority: Boolean,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("mode", mode.name)
            putFiniteOrNull("smb_u", smbU)
            // The demand BEFORE this arbiter ran. The recursive resolver overwrites its own demand
            // with the lifted value straight after, so this is the only record of the pre-lift number.
            putFiniteOrNull("demand_before_u", demandBeforeU)
            putFiniteOrNull("mpc_demand_u", mpcDemandU)
            putFiniteOrNull("catalog_proposed_cap_u", catalogProposedCapU)
            val capKindName = catalogCapKind?.name
            if (capKindName != null) {
                put("catalog_cap_kind", capKindName)
            } else {
                put("catalog_cap_kind", JsonNull)
            }
            putFiniteOrNull("envelope_max_u", envelopeMaxU)
            put("insulin_intent", insulinIntent.name)
            put("reasons", JsonArray(reasons.map { JsonPrimitive(it) }))
            put("adds_smb_authority", addsSmbAuthority)
            put("source", "harmonia_smb_authority_v1")
        }
}

/**
 * Pure arbiter: soft meal proposal + confirmed rise → may lift toward MPC within hard envelope.
 *
 * The envelope is the user's high-BG SMB ceiling (`maxSMBHB`), intersected with the IOB headroom by
 * the caller. A LIFT may reach that full ceiling — it is not restricted to a fraction of it. The
 * catalogue's meal patterns still propose a lower SOFT cap; that cap is what a LIFT is allowed to
 * pass, and HARD caps re-bind after the arbiter, so a lift can never exceed them.
 *
 * ⚠️ ASYNC IMPACT: this runs same-tick on the dose path. External Auditor remains advisory only
 * (CONFIRM/SOFTEN next bias) and must never be required to authorize a lift.
 */
object HarmoniaSmbArbiter {

    fun decide(
        demandBeforeU: Double,
        mpcDemandU: Double,
        catalogProposedCapU: Double?,
        catalogCapKind: PatternCapKind?,
        envelopeMaxU: Double,
        insulinIntent: InsulinIntent,
        harmoniaAction: String?,
        riseConfirmed: Boolean,
        mealCertaintySupports: Boolean,
        protectiveBlock: Boolean,
        barrierPermittedU: Double? = null,
    ): HarmoniaSmbAuthorityDecision {
        val envelope = envelopeMaxU.coerceAtLeast(0.0)
        val before = demandBeforeU.coerceAtLeast(0.0)
        val mpc = mpcDemandU.coerceAtLeast(0.0)
        val softMeal = catalogCapKind == PatternCapKind.SOFT && catalogProposedCapU != null
        val softCap = catalogProposedCapU ?: 0.0
        val reasons = mutableListOf<String>()

        if (protectiveBlock ||
            harmoniaAction == HarmoniaAction.PROTECTIVE_REDUCTION.name ||
            insulinIntent == InsulinIntent.PROTECTIVE
        ) {
            val reduced = if (softMeal) min(before, softCap) else before
            val bounded = min(reduced, envelope)
            reasons += "PROTECTIVE_OR_BLOCK"
            if (bounded + 1e-6 < before) reasons += "HARMONIA_SMB_REDUCE"
            return HarmoniaSmbAuthorityDecision(
                mode = if (bounded + 1e-6 < before) HarmoniaSmbAuthorityMode.REDUCE else HarmoniaSmbAuthorityMode.ACCEPT,
                smbU = bounded,
                demandBeforeU = before,
                mpcDemandU = mpc,
                catalogProposedCapU = catalogProposedCapU,
                catalogCapKind = catalogCapKind,
                envelopeMaxU = envelope,
                insulinIntent = insulinIntent,
                reasons = reasons,
                addsSmbAuthority = bounded + 1e-6 < before,
            )
        }

        val wantsMoreInsulin =
            insulinIntent == InsulinIntent.NEED_MORE_INSULIN ||
                insulinIntent == InsulinIntent.MEAL_SUPPORT ||
                harmoniaAction == HarmoniaAction.MEAL_SUPPORT.name
        val liftEligible =
            softMeal &&
                riseConfirmed &&
                mealCertaintySupports &&
                wantsMoreInsulin &&
                max(mpc, before) > softCap + 1e-6

        if (liftEligible) {
            val wanted = min(max(mpc, before), envelope)
            // The barrier bounds the LIFT only, never the ACCEPT/REDUCE arms. Clamping the whole dose
            // to the barrier was measured to remove 38 % of a day's SMB (see AIMI_NEXT_SESSION Part
            // A-bis correction 5) and the barrier's insulin model is still open, so a lift may never
            // take the dose past what the barrier permits, but it also never lowers what the
            // ungoverned path was already going to deliver.
            val barrierBound = barrierPermittedU?.takeIf { it.isFinite() && it >= 0.0 }
            val lifted = if (barrierBound != null) max(before, min(wanted, barrierBound)) else wanted
            reasons += "RISE_CONFIRMED"
            reasons += "CATALOG_SOFT_PROPOSAL"
            reasons += "MPC_ABOVE_SOFT_CAP"
            reasons += "LIFT_WITHIN_ENVELOPE"
            if (barrierBound != null && wanted > barrierBound + 1e-6) {
                reasons += "LIFT_BARRIER_BOUND_${aimiFmt2(barrierBound)}"
            }
            catalogProposedCapU?.let { reasons += "CATALOG_SOFT_${aimiFmt2(it)}" }
            reasons += "ENVELOPE_${aimiFmt2(envelope)}"
            return HarmoniaSmbAuthorityDecision(
                mode = HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE,
                smbU = lifted,
                demandBeforeU = before,
                mpcDemandU = mpc,
                catalogProposedCapU = catalogProposedCapU,
                catalogCapKind = catalogCapKind,
                envelopeMaxU = envelope,
                insulinIntent = insulinIntent,
                reasons = reasons,
                // Arbitration is active on LIFT even when demand was already at envelope.
                addsSmbAuthority = true,
            )
        }

        val accepted = min(before, envelope)
        if (softMeal) reasons += "ACCEPT_SOFT_PROPOSAL_CONTEXT"
        else if (catalogCapKind == PatternCapKind.HARD) reasons += "HARD_CATALOG_ALREADY_BOUND"
        else reasons += "ACCEPT_CURRENT_DEMAND"
        return HarmoniaSmbAuthorityDecision(
            mode = HarmoniaSmbAuthorityMode.ACCEPT,
            smbU = accepted,
            demandBeforeU = before,
            mpcDemandU = mpc,
            catalogProposedCapU = catalogProposedCapU,
            catalogCapKind = catalogCapKind,
            envelopeMaxU = envelope,
            insulinIntent = insulinIntent,
            reasons = reasons,
            addsSmbAuthority = false,
        )
    }
}
