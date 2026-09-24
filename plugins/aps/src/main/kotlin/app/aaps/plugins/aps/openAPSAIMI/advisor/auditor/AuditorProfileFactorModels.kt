package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import org.json.JSONArray
import org.json.JSONObject

/**
 * Names, bounds and tolerances shared by the auditor profile-factor code.
 *
 * The numbers written here are also written in the preference summary
 * `pref_summary_aimi_auditor_profile_factors`. Change one and the other must follow.
 */
internal object AuditorProfileFactorLimits {

    /** Schema name of the per-tick block written as `adjustments.auditor_profile_factors`. */
    const val AUDIT_SCHEMA_V1: String = "auditor_profile_factors_v1"

    /** Record type of the one line written per profile factor proposal. */
    const val PROPOSAL_RECORD_TYPE: String = "auditor_profile_proposal"

    /**
     * How far the commanded ISF must sit above the pre-floor value before we call it "on the floor",
     * mg/dL per U. Only there to absorb rounding, not a real band.
     */
    const val FLOOR_MATCH_TOLERANCE_MGDL: Double = 0.01

    /** Status of the block when no factor proposal exists. */
    const val STATUS_NO_PROPOSAL: String = "no_proposal"

    /** Status of the block when a proposal exists but asks for nothing. */
    const val STATUS_NEUTRAL: String = "neutral"

    /** Status of the block when a proposal is young enough to be judged this tick. */
    const val STATUS_ACTIVE: String = "active"

    /** Status of the block when the proposal is too old for its direction. */
    const val STATUS_EXPIRED: String = "expired"

    /** Smallest factor the loop will ever use. More insulin. */
    const val FACTOR_MIN: Double = 0.85

    /** Largest factor the loop will ever use. Less insulin. */
    const val FACTOR_MAX: Double = 1.15

    /** A factor this close to 1.0 is noise, not a proposal. */
    const val NEUTRAL_BAND: Double = 0.01

    /** A factor that asks for more insulin lives 15 minutes, counted from the audited tick. */
    const val RAISE_MAX_AGE_MS: Long = 15 * 60_000L

    /** A factor that asks for less insulin lives 30 minutes, counted from the audited tick. */
    const val PROTECT_MAX_AGE_MS: Long = 30 * 60_000L

    /** Smallest allowed error on a glucose claim, mg/dL. */
    const val CLAIM_BG_TOLERANCE_MGDL: Double = 5.0

    /** Allowed error on a glucose claim as a part of the value, when that is larger. */
    const val CLAIM_BG_TOLERANCE_FRACTION: Double = 0.05

    /** Smallest allowed error on an insulin claim, U. */
    const val CLAIM_U_TOLERANCE: Double = 0.2

    /** Allowed error on an insulin claim as a part of the value, when that is larger. */
    const val CLAIM_U_TOLERANCE_FRACTION: Double = 0.10

    /** A window whose glucose fell by more than this did not show resistance, mg/dL. */
    const val BG_FALL_IN_WINDOW_MGDL: Double = 5.0

    /**
     * The measured ISF must be at least this much smaller than the one the engine used before we
     * call it resistance. Same number as [FACTOR_MIN]: we ask for the effect we would allow.
     */
    const val RESISTANCE_RATIO: Double = 0.85

    /**
     * Noise level at which the sensor is not trusted for a dose-raising factor.
     *
     * Same test the engine already uses for its "CGM calibrating" reason in `DetermineBasalAIMI2`.
     */
    const val CGM_NOISE_UNTRUSTED: Double = 3.0

    /** Lowest glucose target a dose formula may be given, mg/dL (`HardLimits.LIMIT_TARGET_BG`). */
    const val TARGET_MIN_MGDL: Double = 80.0

    /** Highest glucose target a dose formula may be given, mg/dL (`HardLimits.LIMIT_TARGET_BG`). */
    const val TARGET_MAX_MGDL: Double = 200.0

    /** How many 5-minute buckets the 30-minute context has. */
    const val CONTEXT_POINTS: Int = 7

    /** How many of those buckets must hold a tick before the context may be used. */
    const val CONTEXT_MIN_POINTS: Int = 5

    /** Length of one context bucket, ms. */
    const val CONTEXT_BUCKET_MS: Long = 5 * 60_000L

    /** Longest prompt JSON we send for the context. */
    const val CONTEXT_JSON_MAX_CHARS: Int = 3000

    /** Longest rationale we keep from the model. */
    const val RATIONALE_MAX_CHARS: Int = 240

    /**
     * Shortest time between two profile factor requests, ms.
     *
     * The profile check is a SECOND call on the same provider key as the audit verdict, and that
     * verdict moves real doses in soft modulation. A quota or a rate limit burned by the shadow would
     * reach today's dosing through a failed verdict, so the shadow is limited to one call every 15
     * minutes. That is also the shortest life of a factor, so nothing useful is lost.
     */
    const val REQUEST_MIN_INTERVAL_MS: Long = 15 * 60_000L
}

/**
 * Every reason a factor can be refused, and every value of `target.path`.
 *
 * The rules never stop at the first refusal: all of them run, so the record shows every reason. That
 * is what makes it possible to count later which rule really blocked the channel.
 */
internal object AuditorProfileFactorCodes {

    // Arrival rules (judged once, when the answer comes back).
    const val LLM_ERROR_PREFIX: String = "llm_error:"
    const val ISF_CLAMPED: String = "isf_clamped"
    const val TARGET_CLAMPED: String = "target_clamped"
    const val MIXED_DIRECTION: String = "mixed_direction"
    const val REASON_MISMATCH: String = "reason_mismatch"
    const val LOW_CONFIDENCE: String = "low_confidence"
    const val CONTEXT_INCOMPLETE: String = "context_incomplete"
    const val CLAIM_MISSING_PREFIX: String = "claim_missing:"
    const val CLAIM_MISMATCH_PREFIX: String = "claim_mismatch:"
    const val CARBS_OR_MEAL_MODE: String = "carbs_or_meal_mode"
    const val MEAL_CERTAINTY: String = "meal_certainty"
    const val COMPETING_HYPOTHESIS: String = "competing_hypothesis"
    const val NOT_ENOUGH_INSULIN: String = "not_enough_insulin"
    const val NO_RESISTANCE_EVIDENCE: String = "no_resistance_evidence"
    const val BG_FELL_IN_WINDOW: String = "bg_fell_in_window"

    // Tick rules (judged again on every tick).
    const val EXPIRED: String = "expired"
    const val BG_BELOW_120: String = "bg_below_120"
    const val BG_FALLING: String = "bg_falling"
    const val PREDICTED_LOW: String = "predicted_low"
    const val POST_HYPO: String = "post_hypo"
    const val EXERCISE: String = "exercise"
    const val CGM_NOISE: String = "cgm_noise"
    const val PROFILE_FLOOR: String = "profile_floor"
    const val NO_PROFILE_STATIC: String = "no_profile_static"
    const val NO_PREDICTION: String = "no_prediction"
    const val NO_GLUCOSE_HISTORY: String = "no_glucose_history"
    const val COMBINED_BOUND: String = "combined_bound"
    const val TARGET_FLOOR: String = "target_floor"
    const val SHADOW: String = "shadow"

    // Where the target factor got to on this tick.
    const val PATH_NOT_REACHED: String = "not_reached"
    const val PATH_SHADOW: String = "shadow"
    const val PATH_APPLIED: String = "applied"

    // The two dose formulas that may see an adjusted target.
    const val DOSE_SITE_SMB: String = "smb"
    const val DOSE_SITE_BASAL: String = "basal"
}

/** Which way a proposal moves the dose. */
enum class ProfileFactorDirection {

    /** Nothing is proposed. */
    NONE,

    /** More insulin: ISF below 1.0, target below 1.0. */
    RAISE,

    /** Less insulin: ISF above 1.0, target above 1.0. */
    PROTECT,
}

/** Why the model says it proposes a factor. */
enum class ProfileFactorReasonCode {

    /** No reason given, or a reason we do not know. */
    NONE,

    /** The body needed more insulin than the loop gave. */
    RESISTANCE_UNDER_CORRECTION,

    /** The body needed less insulin than the loop gave. */
    SENSITIVITY_OVER_CORRECTION,
}

/**
 * The six numbers the model must copy from the data it was shown.
 *
 * Kotlin re-computes each one from the same 30-minute context. A single value that does not match
 * refuses both factors: it means the model did not read the data it was given.
 *
 * Read this for what it is: a transcription check. All six are printed in the prompt, so passing it
 * proves the model looked at the data block, not that its conclusion follows from it. The rules that
 * really guard the dose-raising direction live in `AuditorProfileFactorValidator.checkRaiseEvidence`.
 */
data class ProfileFactorClaims(
    val bgStartMgdl: Double?,
    val bgEndMgdl: Double?,
    val bgMinMgdl: Double?,
    val iobStartU: Double?,
    val iobEndU: Double?,
    val insulinDeliveredU: Double?,
) {

    companion object {

        /** All six missing. Used when the call or the parse failed. */
        val EMPTY: ProfileFactorClaims = ProfileFactorClaims(null, null, null, null, null, null)
    }
}

/**
 * What the model answered, after the parser made every field safe.
 *
 * The two factors are already 1.0 when the model sent something we could not read. They are NOT
 * clamped yet: the clamp belongs to `AuditorProfileFactorValidator`, so the log can show how far
 * outside the bounds the model went.
 *
 * @param failure null when the call and the parse both worked, else a short reason.
 */
data class AuditorProfileFactorLlmOutput(
    val isfFactorRaw: Double,
    val targetFactorRaw: Double,
    val reasonCode: ProfileFactorReasonCode,
    val confidence: Double,
    val competingHypothesis: String,
    val claims: ProfileFactorClaims,
    val rationale: String,
    val parseIssues: List<String>,
    val failure: String?,
) {

    companion object {

        /** A neutral answer that says why there is no answer. */
        fun failed(reason: String): AuditorProfileFactorLlmOutput = AuditorProfileFactorLlmOutput(
            isfFactorRaw = 1.0,
            targetFactorRaw = 1.0,
            reasonCode = ProfileFactorReasonCode.NONE,
            confidence = 0.0,
            competingHypothesis = "none",
            claims = ProfileFactorClaims.EMPTY,
            rationale = "",
            parseIssues = emptyList(),
            failure = reason,
        )
    }
}

/**
 * One claim of the model next to the number Kotlin computed for it.
 *
 * @param key the claim name, as the prompt spells it.
 * @param llmValue what the model said, or null when it said nothing.
 * @param dataValue what Kotlin computed, or null when the context has no such value.
 * @param ok true when the two agree inside the tolerance.
 */
data class ClaimCheckRow(
    val key: String,
    val llmValue: Double?,
    val dataValue: Double?,
    val ok: Boolean,
)

/**
 * A validated proposal, ready to be judged again on every tick.
 *
 * The two factors here already passed the arrival rules. They are still judged tick by tick against
 * the live glucose state, the floors and the combined bound, so this object is a permission to ask,
 * never a permission to dose.
 *
 * @param contextBuiltAtMs timestamp of the audited tick. Every age is measured from it, not from the
 *   moment the answer came back, so a slow answer is born old.
 */
data class AuditorProfileProposal(
    val auditId: String,
    val contextBuiltAtMs: Long,
    val receivedAtMs: Long,
    val isfFactor: Double,
    val targetFactor: Double,
    val direction: ProfileFactorDirection,
    val reasonCode: ProfileFactorReasonCode,
    val confidence: Double,
    val refusedBy: List<String>,
    val claimCheck: List<ClaimCheckRow>,
    val llm: AuditorProfileFactorLlmOutput,
    val keyOnAtArrival: Boolean,
    val providerName: String,
    val mainVerdictName: String,
    val latencyMs: Long,
    val contextJson: String,
) {

    /** True when this proposal asks for nothing at all. */
    val neutral: Boolean get() = isfFactor == 1.0 && targetFactor == 1.0

    /**
     * The one JSONL line written per proposal.
     *
     * @param parentEventId the `event_id` of the audited tick, captured at the call site because the
     *   loop resets its own copy on the next tick.
     */
    fun toJsonLine(parentEventId: String): String = JSONObject().apply {
        put("record_type", AuditorProfileFactorLimits.PROPOSAL_RECORD_TYPE)
        put("parent_event_id", parentEventId)
        put("timestamp", receivedAtMs)
        put("context_built_at_ms", contextBuiltAtMs)
        put("latency_ms", latencyMs)
        put("provider", providerName)
        put("key_on_at_arrival", keyOnAtArrival)
        put("main_verdict", mainVerdictName)
        put(
            "llm",
            JSONObject().apply {
                put("isf_raw", llm.isfFactorRaw)
                put("target_raw", llm.targetFactorRaw)
                put("reason_code", llm.reasonCode.name)
                put("confidence", llm.confidence)
                put("competing_hypothesis", llm.competingHypothesis)
                put("rationale", llm.rationale)
                put(
                    "claims",
                    JSONObject().apply {
                        put("bg_start_mgdl", llm.claims.bgStartMgdl ?: JSONObject.NULL)
                        put("bg_end_mgdl", llm.claims.bgEndMgdl ?: JSONObject.NULL)
                        put("bg_min_mgdl", llm.claims.bgMinMgdl ?: JSONObject.NULL)
                        put("iob_start_u", llm.claims.iobStartU ?: JSONObject.NULL)
                        put("iob_end_u", llm.claims.iobEndU ?: JSONObject.NULL)
                        put("insulin_delivered_u", llm.claims.insulinDeliveredU ?: JSONObject.NULL)
                    },
                )
                put("parse_issues", JSONArray(llm.parseIssues))
                put("failure", llm.failure ?: JSONObject.NULL)
            },
        )
        put(
            "validated",
            JSONObject().apply {
                put("isf", isfFactor)
                put("target", targetFactor)
                put("direction", direction.name)
                put("refused_by", JSONArray(refusedBy))
            },
        )
        put(
            "claim_check",
            JSONArray().apply {
                claimCheck.forEach { row ->
                    put(
                        JSONObject().apply {
                            put("key", row.key)
                            put("llm", row.llmValue ?: JSONObject.NULL)
                            put("data", row.dataValue ?: JSONObject.NULL)
                            put("ok", row.ok)
                        },
                    )
                }
            },
        )
        put("context", runCatching { JSONObject(contextJson) }.getOrElse { JSONObject() })
        put("advisory_only", !keyOnAtArrival)
    }.toString()
}

/**
 * Everything the second request needs, captured on the loop thread before it is sent.
 *
 * The loop hands this over and forgets it: the auditor coroutine must never read loop state again,
 * because by the time the answer comes back the loop has moved on by one or more ticks.
 *
 * @param auditEventId the `event_id` of the audited tick, captured at the call site.
 * @param ticks the ring plus the audited tick, oldest first.
 * @param keyOn the opt-in key at the moment of the request. The request is sent either way; this
 *   only says whether the answer may reach a dose.
 */
/**
 * How often the second request may be sent.
 *
 * Pure, so the rule can be tested without a network or a provider. See
 * [AuditorProfileFactorLimits.REQUEST_MIN_INTERVAL_MS] for why the limit exists.
 */
internal object AuditorProfileFactorRateLimit {

    /**
     * @param lastRequestMs when the last profile request was sent, 0 when there was none yet.
     * @return true when a new request may be sent now.
     */
    fun allow(nowMs: Long, lastRequestMs: Long): Boolean =
        lastRequestMs <= 0L ||
            nowMs - lastRequestMs >= AuditorProfileFactorLimits.REQUEST_MIN_INTERVAL_MS ||
            nowMs < lastRequestMs
}

data class AuditorProfileFactorRequest(
    val auditEventId: String,
    val ticks: List<AuditorTickFact>,
    val mealCertainty: MealCertainty?,
    val mealModeName: String?,
    val minBg75mMgdl: Double,
    val cgmNoise: Double,
    val keyOn: Boolean,
    val contextBuiltAtMs: Long,
)

/**
 * The dose-facing ISF of the tick, as the gate must see it.
 *
 * Since commit 6a6561caab the stress ISF floor is applied to this very value, at the last step of
 * `WorkingIsf.finalize`. The auditor factor is applied after that floor and may never pull the value
 * back under it, which is why the floor is carried here and not looked up again.
 *
 * @param workingMgdl the dose-facing sensitivity after every engine step, mg/dL per U.
 * @param stressFloorMgdl the stress floor offered this tick, or null when none is armed.
 * @param profileStaticMgdl the profile ISF of this hour. Half of it is the floor that holds whether
 *   or not the stress floor is armed, so a null here refuses the dose-raising direction.
 */
data class AuditorIsfRaw(
    val workingMgdl: Double,
    val stressFloorMgdl: Double?,
    val profileStaticMgdl: Double?,
)

/**
 * The live glucose state of the tick, the only thing that may unlock more insulin.
 *
 * At the early point the predictions of this tick do not exist yet, so the minimum predicted glucose
 * comes from the previous tick through the ring. [minPredFromPreviousTick] says which it is.
 *
 * Three fields are nullable, and null always means "we do not know". A rule that guards against a
 * low must REFUSE on an unknown input, never pass it: the ring is empty after the plugin is rebuilt,
 * a tick that ended early leaves no prediction behind, and the bucketed glucose table is empty after
 * a sensor gap. Each of those would otherwise read as "all clear" at the exact moment it is least
 * true.
 *
 * @param minPredBgMgdl the lowest predicted glucose, or null when no prediction is available.
 * @param minBg75mMgdl the lowest glucose of the last 75 minutes, or null when the glucose history
 *   cannot answer (empty table, or only gap-filled rows).
 */
data class TickSafety(
    val bgMgdl: Double,
    val deltaMgdl5m: Double,
    val shortAvgDeltaMgdl5m: Double,
    val cgmNoise: Double,
    val hypoThresholdMgdl: Double,
    val minPredBgMgdl: Double?,
    val minPredThresholdMgdl: Double?,
    val minPredFromPreviousTick: Boolean,
    val postHypoActive: Boolean,
    val minBg75mMgdl: Double?,
    val exerciseLockout: Boolean,
)

/**
 * What the gate decided about the ISF for one tick.
 *
 * @param requested the factor the proposal asked for, 1.0 when there is none.
 * @param effective the factor that really multiplies the working ISF, after the safety rules and the
 *   floor. With the key off this is the value the loop WOULD have used.
 * @param applied true only when the value really reached a dose.
 */
data class IsfTickDecision(
    val requested: Double,
    val effective: Double,
    val lowerBoundMgdl: Double?,
    val workingRawMgdl: Double?,
    val workingAdjustedMgdl: Double?,
    val refusedBy: List<String>,
    val applied: Boolean,
    val ageMs: Long?,
) {

    companion object {

        /** Nothing proposed, nothing refused, nothing applied. */
        val NEUTRAL: IsfTickDecision = IsfTickDecision(
            requested = 1.0,
            effective = 1.0,
            lowerBoundMgdl = null,
            workingRawMgdl = null,
            workingAdjustedMgdl = null,
            refusedBy = emptyList(),
            applied = false,
            ageMs = null,
        )
    }
}

/**
 * What the gate decided about the glucose target for one tick.
 *
 * @param effective the ratio the two dose formulas apply to whatever target they hold.
 * @param targetMgdl the working target after the ratio, the value written to the log.
 * @param combinedBudgetMgdl how many mg/dL the target was still allowed to drop after the ISF took
 *   its share, or null when the direction is protective.
 */
data class TargetTickDecision(
    val requested: Double,
    val effective: Double,
    val targetMgdl: Double,
    val errorMgdl: Double,
    val combinedBudgetMgdl: Double?,
    val refusedBy: List<String>,
    val applied: Boolean,
    val ageMs: Long?,
) {

    companion object {

        /** Nothing proposed, nothing refused, nothing applied. */
        val NEUTRAL: TargetTickDecision = TargetTickDecision(
            requested = 1.0,
            effective = 1.0,
            targetMgdl = 0.0,
            errorMgdl = 0.0,
            combinedBudgetMgdl = null,
            refusedBy = emptyList(),
            applied = false,
            ageMs = null,
        )
    }
}

/**
 * The ISF and the target of one tick, at the levels the auditor must be told apart.
 *
 * Until now the auditor was sent `profile.sens` under the name `isfProfile` and
 * `profile.variable_sens` under the name `isfUsed`. Both are dynamic values, so the model could not
 * see the user profile at all, and it could not see that a floor had raised the commanded value.
 * The same went for the target: it was sent the profile (or temporary) target, never the target the
 * engine really aims at.
 *
 * @param isfProfileStatic the ISF block of the user profile for this time of day, mg/dL per U.
 * @param isfDynamic the dynamic ISF of this tick (`profile.variable_sens`), mg/dL per U.
 * @param isfCommand the ISF the loop really commands (`profile.sens`), mg/dL per U.
 * @param isfCommandOverProfile `isfCommand / isfProfileStatic`, or null when the profile is unknown.
 * @param isfOnProfileFloor true when a floor raised the commanded ISF above what the chain asked for.
 * @param targetProfile the profile target, or the temporary target while one runs, mg/dL.
 * @param targetWorking the target the engine works with this tick, or null when the tick ended
 *   before the engine set it.
 */
data class SnapshotIsfTargetLevels(
    val isfProfileStatic: Double?,
    val isfDynamic: Double,
    val isfCommand: Double,
    val isfCommandOverProfile: Double?,
    val isfOnProfileFloor: Boolean,
    val targetProfile: Double,
    val targetWorking: Double?,
)

/**
 * The ISF and target levels of the running tick, plus the state of the opt-in key.
 *
 * One fresh instance per tick, held by `DetermineBasalAIMI2`. The values are read where the loop
 * already has them in hand, so nothing downstream has to read a process-global again:
 * - the ISF levels and the profile target are taken once, where the decision context is built;
 * - the working target is taken where the basal schedule sets it, which many ticks never reach.
 *
 * Observation only. No dose reads this object.
 */
class AuditorProfileTickState {

    /** ISF block of the user profile for this time of day, mg/dL per U. */
    var profileStaticIsfMgdl: Double? = null

    /** Dynamic ISF of this tick (`profile.variable_sens`), mg/dL per U. */
    var dynamicIsfMgdl: Double? = null

    /** ISF the loop commands this tick (`profile.sens`), mg/dL per U. */
    var commandIsfMgdl: Double? = null

    /** Commanded ISF before the profile-relative floor, mg/dL per U (`CommandedIsf`). */
    var commandPreFloorIsfMgdl: Double? = null

    /**
     * The floor used on the commanded ISF, as a fraction of the profile ISF: 0.5 normally, 1.0 while
     * the stress floor holds and its key is armed.
     */
    var commandFloorMultiplier: Double? = null

    /** Profile target, or the temporary target while one runs, mg/dL. */
    var profileTargetMgdl: Double? = null

    /** True while a temporary target runs. */
    var tempTargetActive: Boolean = false

    /** Target the engine works with, mg/dL. Null while the tick has not reached it. */
    var workingTargetMgdl: Double? = null

    /** State of the opt-in key for this tick. */
    var keyOn: Boolean = false

    /** The proposal this tick worked from, or null when there was none. */
    var proposal: AuditorProfileProposal? = null

    /** What the gate decided about the ISF. Null until the early gate has run. */
    var isfDecision: IsfTickDecision? = null

    /** What the gate decided about the target. Null while the tick has not reached that point. */
    var targetDecision: TargetTickDecision? = null

    /**
     * The factor that really multiplied the dose-facing ISF, 1.0 when nothing was applied.
     *
     * With the key off this stays exactly 1.0, whatever the shadow decision holds.
     */
    var isfAppliedFactor: Double = 1.0

    /**
     * Which of `not_reached`, `shadow` and `applied` describes the ISF this tick.
     *
     * A tick that ends before the sensitivity is final never reaches the apply step, and then this
     * says `not_reached` and `isf.applied` is false. The shadow study joins on those two fields, so
     * they have to mean exactly what they say.
     */
    var isfPath: String = AuditorProfileFactorCodes.PATH_NOT_REACHED

    /** Which of `not_reached`, `shadow` and `applied` describes the target this tick. */
    var targetPath: String = AuditorProfileFactorCodes.PATH_NOT_REACHED

    /** The dose formulas that really used the adjusted target this tick. */
    val targetDoseSites: MutableSet<String> = linkedSetOf()

    /** Live glucose state read at the early ISF point. */
    var safetyAtA: TickSafety? = null

    /** Live glucose state read at the target point. */
    var safetyAtB: TickSafety? = null

    /** `commandIsfMgdl / profileStaticIsfMgdl`, or null when the profile ISF is unknown. */
    val commandOverProfile: Double?
        get() {
            val command = commandIsfMgdl ?: return null
            val profileIsf = profileStaticIsfMgdl ?: return null
            if (!profileIsf.isFinite() || profileIsf <= 0.0 || !command.isFinite()) return null
            return command / profileIsf
        }

    /**
     * True when the floor raised the commanded ISF.
     *
     * The chain asked for `commandPreFloorIsfMgdl` and the loop commanded more than that, so the
     * lower ISF the chain wanted was not allowed. False when the two values match and when the
     * pre-floor value is unknown.
     */
    val onProfileFloor: Boolean
        get() {
            val preFloor = commandPreFloorIsfMgdl ?: return false
            val command = commandIsfMgdl ?: return false
            if (!preFloor.isFinite() || !command.isFinite()) return false
            return command > preFloor + AuditorProfileFactorLimits.FLOOR_MATCH_TOLERANCE_MGDL
        }

    /**
     * The levels to send to the auditor, or null when the key is off or the levels were not captured.
     *
     * With the key off the auditor keeps the fields it has always been sent, so its prompt does not
     * change at all.
     */
    fun snapshotLevelsIfArmed(): SnapshotIsfTargetLevels? {
        if (!keyOn) return null
        val dynamic = dynamicIsfMgdl?.takeIf { it.isFinite() } ?: return null
        val command = commandIsfMgdl?.takeIf { it.isFinite() } ?: return null
        val profileTarget = profileTargetMgdl?.takeIf { it.isFinite() } ?: return null
        return SnapshotIsfTargetLevels(
            isfProfileStatic = profileStaticIsfMgdl?.takeIf { it.isFinite() },
            isfDynamic = dynamic,
            isfCommand = command,
            isfCommandOverProfile = commandOverProfile,
            isfOnProfileFloor = onProfileFloor,
            targetProfile = profileTarget,
            targetWorking = workingTargetMgdl?.takeIf { it.isFinite() },
        )
    }

    /**
     * The status of this tick: what the loop had in hand and how old it was.
     *
     * `expired` wins over `active` because an expired proposal is the one case a reader must be able
     * to count without joining two records.
     */
    val status: String
        get() {
            val held = proposal ?: return AuditorProfileFactorLimits.STATUS_NO_PROPOSAL
            if (held.neutral) return AuditorProfileFactorLimits.STATUS_NEUTRAL
            val expired = isfDecision?.refusedBy?.contains(AuditorProfileFactorCodes.EXPIRED) == true ||
                targetDecision?.refusedBy?.contains(AuditorProfileFactorCodes.EXPIRED) == true
            return if (expired) AuditorProfileFactorLimits.STATUS_EXPIRED
            else AuditorProfileFactorLimits.STATUS_ACTIVE
        }

    /**
     * The per-tick audit block, written on every tick whether the key is on or off.
     *
     * With the key off the factor fields hold the values the loop WOULD have used, and every
     * `applied` flag is false. That pair is the whole shadow record.
     */
    fun toJsonObject(): JSONObject = JSONObject().apply {
        val held = proposal
        put("schema", AuditorProfileFactorLimits.AUDIT_SCHEMA_V1)
        put("key_on", keyOn)
        put("status", status)
        put("audit_id", held?.auditId ?: JSONObject.NULL)
        put("context_built_at_ms", held?.contextBuiltAtMs ?: JSONObject.NULL)
        put("verdict_age_ms", isfDecision?.ageMs ?: targetDecision?.ageMs ?: JSONObject.NULL)
        put("direction", (held?.direction ?: ProfileFactorDirection.NONE).name)
        put("reason_code", (held?.reasonCode ?: ProfileFactorReasonCode.NONE).name)
        put("llm_confidence", held?.confidence ?: JSONObject.NULL)
        put("arrival_refused_by", JSONArray(held?.refusedBy ?: emptyList<String>()))
        put("request_min_interval_ms", AuditorProfileFactorLimits.REQUEST_MIN_INTERVAL_MS)
        put(
            "isf",
            JSONObject().apply {
                putNumberOrNull("llm_raw", held?.llm?.isfFactorRaw)
                putNumberOrNull("requested", isfDecision?.requested)
                putNumberOrNull("effective", isfDecision?.effective)
                put("applied", isfDecision?.applied ?: false)
                put("path", isfPath)
                put("refused_by", JSONArray(isfDecision?.refusedBy ?: emptyList<String>()))
                putNumberOrNull("profile_static_mgdl", profileStaticIsfMgdl)
                putNumberOrNull("dynamic_raw_mgdl", dynamicIsfMgdl)
                putNumberOrNull("command_raw_mgdl", commandIsfMgdl)
                putNumberOrNull("command_pre_floor_mgdl", commandPreFloorIsfMgdl)
                putNumberOrNull("command_over_profile", commandOverProfile)
                put("on_profile_floor", onProfileFloor)
                putNumberOrNull("floor_multiplier", commandFloorMultiplier)
                putNumberOrNull("lower_bound_mgdl", isfDecision?.lowerBoundMgdl)
                putNumberOrNull("working_raw_mgdl", isfDecision?.workingRawMgdl)
                putNumberOrNull("working_adjusted_mgdl", isfDecision?.workingAdjustedMgdl)
            },
        )
        put(
            "target",
            JSONObject().apply {
                putNumberOrNull("llm_raw", held?.llm?.targetFactorRaw)
                putNumberOrNull("requested", targetDecision?.requested)
                putNumberOrNull("effective", targetDecision?.effective)
                put("applied", targetDecision?.applied ?: false)
                put("refused_by", JSONArray(targetDecision?.refusedBy ?: emptyList<String>()))
                put("path", targetPath)
                put("dose_sites", JSONArray(targetDoseSites.toList()))
                putNumberOrNull("profile_mgdl", profileTargetMgdl)
                put("temp_target_active", tempTargetActive)
                putNumberOrNull("working_raw_mgdl", workingTargetMgdl)
                putNumberOrNull("working_adjusted_mgdl", targetDecision?.targetMgdl)
                putNumberOrNull("error_mgdl", targetDecision?.errorMgdl)
                putNumberOrNull("combined_budget_mgdl", targetDecision?.combinedBudgetMgdl)
            },
        )
        put("tick_safety", (safetyAtB ?: safetyAtA).toJsonOrNull())
    }

    private fun TickSafety?.toJsonOrNull(): Any {
        val safety = this ?: return JSONObject.NULL
        return JSONObject().apply {
            putNumberOrNull("bg_mgdl", safety.bgMgdl)
            putNumberOrNull("delta_mgdl_5m", safety.deltaMgdl5m)
            putNumberOrNull("short_avg_delta_mgdl_5m", safety.shortAvgDeltaMgdl5m)
            putNumberOrNull("hypo_threshold_mgdl", safety.hypoThresholdMgdl)
            putNumberOrNull("min_pred_mgdl", safety.minPredBgMgdl)
            put("min_pred_source", if (safety.minPredFromPreviousTick) "prev_tick" else "this_tick")
            putNumberOrNull("min_bg_75m_mgdl", safety.minBg75mMgdl)
            put("post_hypo", safety.postHypoActive)
            put("exercise", safety.exerciseLockout)
            putNumberOrNull("cgm_noise", safety.cgmNoise)
        }
    }

    /**
     * Writes the value, or an explicit null when it is missing or not a finite number.
     *
     * The field always exists, so a reader can tell "this tick had no value" from "the block was
     * not written". A value that is not finite would make the whole decision line fail to build.
     */
    private fun JSONObject.putNumberOrNull(name: String, value: Double?) {
        put(name, value?.takeIf { it.isFinite() } ?: JSONObject.NULL)
    }
}
