package app.aaps.plugins.aps.openAPSAIMI.replay

import org.json.JSONObject

/**
 * One loop tick projected from an `AIMI_Decisions_Last24h.jsonl` support package.
 *
 * The fixture format is a **flat** projection: one JSON object per line, short keys, only the
 * fields the harness consumes. A full package is 8–13 MB per day, which is not something to
 * version; the projection is around 150 KB and is the input contract of the harness rather than a
 * partial dump of the export.
 *
 * Fields added after a fixture was captured are simply absent, which is why every property is
 * nullable. That is deliberate: an old fixture must stay loadable so a regression can be compared
 * against days recorded before a field existed.
 *
 * The same nullability carries a second, stronger meaning for the `cbf*` group below: the barrier
 * only runs on about a third of real ticks, so those fields are absent on most of a day even in a
 * fresh capture. A missing one means *"the barrier did not run here"* and must never be read as a
 * zero — a zero permitted dose and an absent barrier are opposite facts.
 *
 * See `docs/adr/0001-replay-harness.md`.
 */
data class ReplayTick(
    val timestampMs: Long,
    val trigger: String?,
    val bgMgdl: Double?,
    val iobU: Double?,
    val cobG: Double?,
    /** Historical export field: carries the command sensitivity, not the profile block. */
    val profileIsfMgdl: Double?,
    val profileBasalUph: Double?,
    /** Static profile ISF. Absent from fixtures captured before ADR 0002. */
    val staticIsfMgdl: Double?,
    /** Sensitivity the command used. Absent from fixtures captured before ADR 0002. */
    val commandIsfMgdl: Double?,
    /** Dynamic ISF source. Absent from fixtures captured before ADR 0003. */
    val isfSource: String?,
    val isfAgeMs: Long?,
    val decision: String?,
    val smbU: Double,
    val basalUph: Double?,
    val originOwner: String?,
    val finalOwner: String?,
    val maxSmbU: Double?,
    val iobHeadroomU: Double?,
    val correctionAggressionTier: String?,
    val safetySource: String?,
    val postHypoGuardState: String?,
    val patientMode: String?,
    val targetBgMgdl: Double?,
    val mealModeActive: Boolean?,
    val postHypoActive: Boolean?,
    val safetyGate: String?,
    val haltRemainingPipeline: Boolean?,
    val uamDominant: String?,
    val absorptionPhase: String?,
    val physiologicalPhase: String?,
    val dynamicIsfMgdl: Double?,
    val eventualMgdl: Double?,
    val minPredMgdl: Double?,
    /** Post-hypo delivery authority. Absent from fixtures captured before ADR 0006. */
    val postHypoAuthorityActive: Boolean?,
    val postHypoAuthorityReason: String?,
    val smbBeforeCapU: Double?,
    val smbAfterCapU: Double?,

    /**
     * Minutes since the first tick of the fixture, when the fixture carries no absolute clock.
     *
     * Only the barrier fixture sets it. A day fixture keeps [timestampMs] as an epoch and leaves
     * this `null`.
     */
    val offsetMinutes: Double?,
    /**
     * Glucose slope in mg/dL per minute, taken from the loop's own 5-minute delta.
     *
     * **Not** the `bgVelocity` the barrier saw. The export carries no such field, and the barrier's
     * value comes out of the state estimator after a momentum step. Kept as context only; the
     * barrier replay drives the gamma branch from [cbfActiveGamma] instead of from this number.
     */
    val bgVelocityMgdlPerMin: Double?,
    /** IOB ceiling in force on this tick, in units. */
    val maxIobU: Double?,
    /**
     * Hypo threshold the safety block reported, in mg/dL.
     *
     * Context only. The export carries no LGS threshold, and `ControlBarrierShield.enforce` does
     * not read one — it is an `MpcController` input, upstream of the barrier.
     */
    val lgsThresholdMgdl: Double?,
    /** Total the barrier let through on this tick (SMB plus TBR over 12), in units. */
    val cbfPermittedU: Double?,
    /** Same total, recomputed by the engine's shadow with the coefficient floor removed. */
    val cbfPermittedUnflooredU: Double?,
    /** Coefficient the barrier used, after `InsulinActionModel.controlCoefficient` floored it. */
    val cbfCoefficientUsed: Double?,
    /** Same coefficient before the floor, i.e. `InsulinActionModel.metabolicCoefficient`. */
    val cbfCoefficientUnfloored: Double?,
    /** Sensitivity handed to the barrier, in mg/dL per unit. */
    val cbfProfileIsfMgdl: Double?,

    // --- adjustments.control_barrier: the barrier's own arithmetic, absent when it did not run ---

    /** `h(x)`, the distance to the danger bound, in mg/dL. */
    val cbfHMgdl: Double?,
    /** Lie derivative `L_f(h)`: expected glucose slope with no new insulin, in mg/dL per minute. */
    val cbfLfhMgdlPerMin: Double?,
    /** Lie derivative `L_g(h)`: effect of one unit, in mg/dL per unit per minute. Negative. */
    val cbfLghMgdlPerUPerMin: Double?,
    /** The `-siMetabolic * iob * bg` term of `L_f(h)`, in mg/dL per minute. */
    val cbfInsulinTermMgdlPerMin: Double?,
    /** Coefficient the barrier multiplied by `iob * bg`. Same number as [cbfCoefficientUsed]. */
    val cbfSiMetabolic: Double?,
    /** Gamma actually applied, after the acceleration guard and the meal relaxation. */
    val cbfActiveGamma: Double?,
    /** `-activeGamma * h`. */
    val cbfSafetyBoundary: Double?,
    /** `L_f(h) + L_g(h) * requestedTotal`. */
    val cbfSystemEvolution: Double?,
    /** Dose the barrier allowed, in units, or `null` when it did not have to intervene. */
    val cbfSafeU: Double?,
    /** `true` when the barrier zeroed the whole dose. */
    val cbfFullySuspended: Boolean?,
    /** Whether the sensitivity the barrier used was the dynamic ISF rather than a profile block. */
    val cbfAnchorIsDynamicIsf: Boolean?,
    /** SMB the controller asked for, before the barrier, in units. */
    val cbfMpcRawSmbU: Double?,
    /** TBR the controller asked for, before the barrier, in U/h. */
    val cbfMpcRawTbrUph: Double?,
) {

    /** `true` when this tick carries the whole `control_barrier` block. */
    val hasBarrierBlock: Boolean
        get() = cbfHMgdl != null && cbfLfhMgdlPerMin != null && cbfLghMgdlPerUPerMin != null &&
            cbfInsulinTermMgdlPerMin != null && cbfActiveGamma != null && cbfSiMetabolic != null

    /**
     * Glucose the barrier saw, in mg/dL, read back from `h = bg - 80`.
     *
     * Preferred over [bgMgdl] for a replay. They agree on every tick measured so far, but this one
     * is the barrier's own input rather than the value the export took from the baseline block.
     */
    val cbfBgMgdl: Double? get() = cbfHMgdl?.plus(BG_DANGER_THRESHOLD_MGDL)

    /**
     * IOB the barrier saw, in units, read back from `insulinTerm = -siMetabolic * iob * bg`.
     *
     * Deliberately not [iobU]: the state the barrier receives has been through
     * `AutoDriveState.createSafe`, which clamps a negative IOB to zero. On the ticks where the
     * baseline block reported a slightly negative IOB the barrier reasoned with zero, and a replay
     * has to reason with zero too.
     */
    val cbfIobU: Double?
        get() {
            val si = cbfSiMetabolic ?: return null
            val bg = cbfBgMgdl ?: return null
            val term = cbfInsulinTermMgdlPerMin ?: return null
            if (si <= 0.0 || bg <= 0.0) return null
            return -term / (si * bg)
        }

    /**
     * Carb appearance rate the barrier saw, in mg/dL per minute, read back from `L_f(h)`.
     *
     * `lfh = -0.015 * (bg - 100) - siMetabolic * iob * bg + estimatedRa`, so `Ra` is the only
     * unknown left once the other three terms are known. Deliberately not the exported
     * `estimated_ra_*`: the engine hands the barrier a state that went through the estimator and a
     * momentum step, and on this package neither exported field matched the barrier's own term on
     * more than 8 % of ticks.
     */
    val cbfEstimatedRaMgdlPerMin: Double?
        get() {
            val lfh = cbfLfhMgdlPerMin ?: return null
            val bg = cbfBgMgdl ?: return null
            val term = cbfInsulinTermMgdlPerMin ?: return null
            return lfh + GLUCOSE_CLEARANCE_P1 * (bg - GLUCOSE_BASAL_MGDL) - term
        }

    /**
     * Sensitivity handed to the barrier as `safetySi`, i.e. ISF over 10000.
     *
     * The barrier ignores it whenever a coefficient override is passed, which is what the replay
     * does, but `AutoDriveState` still needs a positive sensitivity to build at all.
     */
    val cbfSafetySi: Double? get() = cbfProfileIsfMgdl?.div(10000.0)

    companion object {

        /** `bgDangerThreshold` of `ControlBarrierShield`, mirrored here because it is private. */
        const val BG_DANGER_THRESHOLD_MGDL: Double = 80.0

        /** `p1` of `ControlBarrierShield`, the basal glucose clearance rate. */
        const val GLUCOSE_CLEARANCE_P1: Double = 0.015

        /** Glucose the clearance term is centred on, in mg/dL. */
        const val GLUCOSE_BASAL_MGDL: Double = 100.0

        fun fromJson(line: String): ReplayTick {
            val o = JSONObject(line)
            fun str(key: String): String? = if (o.has(key) && !o.isNull(key)) o.getString(key) else null
            fun dbl(key: String): Double? = if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
            fun lng(key: String): Long? = if (o.has(key) && !o.isNull(key)) o.getLong(key) else null
            fun bool(key: String): Boolean? = if (o.has(key) && !o.isNull(key)) o.getBoolean(key) else null
            return ReplayTick(
                timestampMs = o.getLong("t"),
                trigger = str("trig"),
                bgMgdl = dbl("bg"),
                iobU = dbl("iob"),
                cobG = dbl("cob"),
                profileIsfMgdl = dbl("pisf"),
                profileBasalUph = dbl("pbasal"),
                staticIsfMgdl = dbl("sisf"),
                commandIsfMgdl = dbl("cisf"),
                isfSource = str("isrc"),
                isfAgeMs = lng("iage"),
                decision = str("dec"),
                smbU = dbl("amt") ?: 0.0,
                basalUph = dbl("basal"),
                originOwner = str("owner"),
                finalOwner = str("fowner"),
                maxSmbU = dbl("maxsmb"),
                iobHeadroomU = dbl("iobhead"),
                correctionAggressionTier = str("tier"),
                safetySource = str("safety"),
                postHypoGuardState = str("phguard"),
                patientMode = str("pmode"),
                targetBgMgdl = dbl("tgt"),
                mealModeActive = bool("mealmode"),
                postHypoActive = bool("posthypo"),
                safetyGate = str("sgate"),
                haltRemainingPipeline = bool("halt"),
                uamDominant = str("uam"),
                absorptionPhase = str("absorb"),
                physiologicalPhase = str("phase"),
                dynamicIsfMgdl = dbl("disf"),
                eventualMgdl = dbl("ev"),
                minPredMgdl = dbl("minpred"),
                postHypoAuthorityActive = bool("phd_active"),
                postHypoAuthorityReason = str("phd_reason"),
                smbBeforeCapU = dbl("phd_before"),
                smbAfterCapU = dbl("phd_after"),
                offsetMinutes = dbl("tmin"),
                bgVelocityMgdlPerMin = dbl("vel"),
                maxIobU = dbl("maxiob"),
                lgsThresholdMgdl = dbl("lgs"),
                cbfPermittedU = dbl("cbfu"),
                cbfPermittedUnflooredU = dbl("cbfuunf"),
                cbfCoefficientUsed = dbl("cbfc"),
                cbfCoefficientUnfloored = dbl("cbfcunf"),
                cbfProfileIsfMgdl = dbl("cbfisf"),
                cbfHMgdl = dbl("cb_h"),
                cbfLfhMgdlPerMin = dbl("cb_lfh"),
                cbfLghMgdlPerUPerMin = dbl("cb_lgh"),
                cbfInsulinTermMgdlPerMin = dbl("cb_ins"),
                cbfSiMetabolic = dbl("cb_si"),
                cbfActiveGamma = dbl("cb_gam"),
                cbfSafetyBoundary = dbl("cb_bnd"),
                cbfSystemEvolution = dbl("cb_evo"),
                cbfSafeU = dbl("cb_safeu"),
                cbfFullySuspended = bool("cb_susp"),
                cbfAnchorIsDynamicIsf = bool("cb_anch"),
                cbfMpcRawSmbU = dbl("cb_rsmb"),
                cbfMpcRawTbrUph = dbl("cb_rtbr"),
            )
        }
    }
}
