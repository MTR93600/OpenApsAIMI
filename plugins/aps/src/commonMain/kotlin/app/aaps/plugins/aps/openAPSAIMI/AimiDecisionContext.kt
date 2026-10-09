package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.quality.IobSurveillanceExport
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiJson
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonArr
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonObj
import kotlinx.serialization.json.JsonObject

internal data class AimiDecisionContext(
    val event_id: String,
    val timestamp: Long,
    val trigger: String,
    val baseline_state: BaselineState,
    val adjustments: Adjustments = Adjustments(),
    var outcome: Outcome? = null
) {
    data class BaselineState(
        /**
         * Historical field. Despite its name it carries `profile.sens`, which is the **command**
         * sensitivity (dynamic ISF x physiological factor), not the profile block value. Kept
         * populated so existing analysis keeps working; use [command_isf_mgdl] in new work and
         * [profile_isf_static_mgdl] when a static baseline is needed.
         * See `docs/adr/0002-sensitivity-three-levels.md`.
         */
        val profile_isf_mgdl: Double,
        val profile_basal_uph: Double,
        val current_bg_mgdl: Double,
        val cob_g: Double,
        val iob_u: Double,
        /** User profile ISF block for this time of day. Static within the tick. */
        val profile_isf_static_mgdl: Double? = null,
        /**
         * `ctx.profile.sens`, read once at tick bootstrap. Same value as [profile_isf_mgdl] on every
         * tick, because both are that one read.
         *
         * This is **not** the sensitivity the SMB dose used. It reaches two places only: the control
         * barrier anchor in `AutodriveEngine.profileAnchoredSafetySi`, and the endogenous basal
         * bridge in `EndogenousBasalBridgePolicy.computeBridgeRateUph`. The delivered SMB is sized
         * from the fused PKPD sensitivity instead — see [variable_sens_mgdl] for where to read it.
         *
         * Even the barrier rarely reads it: `InsulinActionModel.LEGACY_CONTROL_COEFFICIENT` floors
         * the coefficient at the value for 45 mg/dL/U, and on the 2026-08-15 package the exported
         * `si_metabolic` sat exactly on that floor on 61 of 72 ticks.
         */
        val command_isf_mgdl: Double? = null,
        /** Which source produced the dynamic value this tick. See [IsfSourceTelemetry]. */
        val isf_source: String? = null,
        /** Age (ms) of the cached dynamic entry that was used, when one was used. */
        val isf_age_ms: Long? = null,
        /** Key of the cached entry used. Distinguishes two entries sharing a 30-minute bucket. */
        val isf_cache_key: Long? = null,
        /** Glucose the cached value was computed for (the key's within-bucket remainder). */
        val isf_cache_glucose_mgdl: Long? = null,
        /**
         * Sensitivity the **outcomes** imply, in mg/dL per U, measured as `-dBG / insulin absorbed`
         * over clean falls. See `ObservedSensitivityMeter`.
         *
         * A fall is counted only when it lasts 30 to 120 minutes, drops at least 25 mg/dL, has no
         * carbs on board and no meal in the 30 minutes before it, shows a mean rate of glucose
         * appearance below 0.30 mg/dL/min, and cost at least 0.8 U. The insulin credited is the fall
         * in IOB, plus the basal above the profile rate, plus the SMBs decided inside the window.
         *
         * The median is `null` below three windows, never `0.0`: zero would read as a real
         * sensitivity of zero. [isf_obs_window_count] says how far the instrument is from being able
         * to answer.
         *
         * These fields are **strictly passive**. Nothing in the dosing chain reads them. They exist
         * for one purpose: to be compared with [command_isf_mgdl], so that the question "does the ISF
         * chain estimate the right quantity" can finally be answered from exported data.
         *
         * `var`, and written after this object is built, like the fields below.
         */
        var isf_obs_median_mgdl: Double? = null,
        /** Same measure, night windows only (local hour 0 to 8). */
        var isf_obs_night_median_mgdl: Double? = null,
        /** Same measure, day windows only. */
        var isf_obs_day_median_mgdl: Double? = null,
        /** How many windows the look-back holds. Reported even when the median is `null`. */
        var isf_obs_window_count: Int? = null,
        /** How many of them are night windows. */
        var isf_obs_night_count: Int? = null,
        /** How many of them are day windows. */
        var isf_obs_day_count: Int? = null,
        /** End time of the most recent window, so a reading can be aged. */
        var isf_obs_last_window_end_ms: Long? = null,
        /** Sensitivity of that most recent window alone. */
        var isf_obs_last_window_mgdl: Double? = null,
        /** Fall of that window, mg/dL. */
        var isf_obs_last_window_drop_mgdl: Double? = null,
        /** Insulin credited to that window, U. */
        var isf_obs_last_window_absorbed_u: Double? = null,
        /** Which ISF calculation path was taken this tick (telemetry). */
        var isf_calc_path: String? = null,
        /** Size of the ISF cache (telemetry). */
        var isf_cache_size: Int? = null,
        /** Whether effort-based SMB was armed this tick (telemetry). */
        var effort_smb_armed: Boolean? = null,
        /** Requested meal boost rate in U/h (telemetry). */
        var meal_boost_requested_uph: Double? = null,
        /** Meal boost cap tier name (telemetry). */
        var meal_boost_cap_tier: String? = null,
        /** Maximum allowed meal boost rate in U/h (telemetry). */
        var meal_boost_cap_max_uph: Double? = null,
        /** Requested rate after the tier ceiling, never above requested (telemetry). */
        var meal_boost_capped_uph: Double? = null,
        /** Whether the meal boost cap would bind (telemetry). */
        var meal_boost_cap_would_bind: Boolean? = null,
        /** Engine rate in U/h (telemetry). */
        var engine_rate_uph: Double? = null,
        /** RT rate in U/h (telemetry). */
        var rt_rate_uph: Double? = null,
        /** Merge mode (telemetry). */
        var merge_mode: String? = null,
        /** Merge winner (telemetry). */
        var merge_winner: String? = null,
        /**
         * Shadow late fat damping window: the late part of an absorption episode while a large
         * insulin stack is already working. Strictly passive — nothing in the dosing chain reads it.
         * It exists to be compared with [late_fat_rise_flag] before any wiring is decided.
         */
        var late_fat_damping_window: Boolean? = null,
        /** The old `isLateFatProteinRise` predicate for the same tick, to measure the divergence. */
        var late_fat_rise_flag: Boolean? = null,
        /** Minutes since the absorption episode started, `null` when there is no episode. */
        var late_fat_onset_age_min: Int? = null,
        /**
         * Harmonia counterfactual, strictly passive. Nothing in the dosing chain reads these fifteen
         * fields, and nothing may ever read them: they exist only so that what Harmonia proposed can
         * be compared, after the fact, with what was really done.
         *
         * Harmonia is asked for a plan before the tick knows whether the SMB channel was zeroed for
         * safety. It is then judged on exactly that verdict, and never told it, nor told that it was
         * refused. These fields write down the missing message and its price:
         *
         *  - `harmonia_cf_*` — what Harmonia would propose if it were told, and whether that differs
         *    from what it really proposed. [harmonia_cf_changes_proposal] is the whole question.
         *  - `harmonia_block_*` — the size of the refusal. [harmonia_block_stake_u] is signed:
         *    negative means the refusal **added** insulin, because it refused an under-dose.
         *  - `harmonia_verdict_*` — the mirrored safety verdict.
         *    [harmonia_verdict_known_to_engine] is the witness of inertia: it is written `false`, and
         *    stays `false` for as long as nothing feeds the verdict back into the decision.
         *  - `harmonia_prev_*` — what refused the previous tick, and for how many ticks in a row.
         *  - [harmonia_tree_risk_divergence] — set only when the tree trunk and Harmonia disagree on
         *    the risk level.
         *
         * `var`, and written after this object is built, like the fields above. See
         * `HarmoniaCounterfactual`.
         */
        var harmonia_cf_rule: String? = null,
        /** Action Harmonia would propose knowing the verdict. */
        var harmonia_cf_action: String? = null,
        /** Basal that goes with that action, U/h. */
        var harmonia_cf_basal_uph: Double? = null,
        /** `true` only when knowing the verdict would change the proposal. */
        var harmonia_cf_changes_proposal: Boolean? = null,
        /** The refused request was above the profile basal by more than one pump step. */
        var harmonia_block_was_escalation: Boolean? = null,
        /** Request minus profile basal, U/h, signed. */
        var harmonia_block_delta_uph: Double? = null,
        /** Insulin the refusal moved over the applied duration, U, signed. */
        var harmonia_block_stake_u: Double? = null,
        /** Applied rate minus requested rate, U/h. */
        var harmonia_applied_gap_uph: Double? = null,
        /** Mirrored verdict: a safety rule zeroed the SMB of this tick. */
        var harmonia_verdict_critical_safety: Boolean? = null,
        /** Mirrored verdict: the active context suppresses the SMB of this tick. */
        var harmonia_verdict_context_suppress: Boolean? = null,
        /** Mirrored verdict: a manual meal mode is declared, which exempts the channel. */
        var harmonia_verdict_meal_mode: Boolean? = null,
        /** Witness of inertia. Written `false`: the engine is never told the verdict. */
        var harmonia_verdict_known_to_engine: Boolean? = null,
        /** Runtime blocker of the previous tick, `null` when there was none. */
        var harmonia_prev_blocker: String? = null,
        /** How many ticks in a row the same blocker has refused Harmonia. */
        var harmonia_prev_blocked_streak: Int? = null,
        /** `"tronc=X|harmonia=Y"` when the two risk views differ, `null` when they agree. */
        var harmonia_tree_risk_divergence: String? = null,
        /** Fast estimator 1: Kalman-filtered raw ISF. */
        val isf_kalman_fast_mgdl: Double? = null,
        /** Fast estimator 2: IsfAdjustmentEngine output. */
        val isf_adj_engine_mgdl: Double? = null,
        /** Slow floor: profile/TDD fusion scaled by PKPD. */
        val isf_fused_slow_mgdl: Double? = null,
        /** Weight given to the fast estimators in the final blend. */
        val isf_trust_fast: Double? = null,
        /** Delta-driven correction factor applied after the blend. */
        val isf_dynamic_factor: Double? = null,
        /** AutoISF-style trajectory multiplier (1.0 when the layer did not fire). */
        val isf_trajectory_multiplier: Double? = null,
        /** Estimated rate of glucose appearance (mg/dL/min) from the continuous state estimator. */
        val estimated_ra_mgdl_per_min: Double? = null,
        /** Physiological ISF factor of the tick, bounds [0.85, 1.15]. Applied once since ADR 0007. */
        val physio_isf_factor: Double? = null,
        /**
         * Commanded sensitivity before the profile-relative floor, mg/dL per U.
         *
         * Next to `command_isf_mgdl` it says how much the floor moved this tick, which no exported
         * field could say while the shadow witness was reading the already-floored value.
         */
        val isf_pre_floor_mgdl: Double? = null,
        /**
         * Stress-ISF-floor signature of the tick: does it hold, and why.
         *
         * Written on every tick whether `BooleanKey.OApsAIMIStressIsfFloor` is armed or not, so the
         * gesture can be measured before it is armed. See `StressIsfFloor`.
         */
        val stress_isf_floor_active: Boolean? = null,
        val stress_isf_floor_reason: String? = null,
        /**
         * Sensitivity commanded with the floor at 1.0 x profile, mg/dL per U.
         *
         * Present whenever the signature is active, armed or not. Absent means "the signature does
         * not hold", not zero.
         */
        val stress_isf_floor_isf_mgdl: Double? = null,
        /**
         * Awake resting heart rate the signature was measured against, bpm.
         *
         * Absent means the gesture stood down for want of data. It is not the same quantity as the
         * exported `rhr_resting_bpm`, which is the lowest SLEEPING value of the last seven days and
         * was pinned at 50 on every tick of three packages. See `AwakeRestingHeartRate`.
         */
        val stress_floor_awake_resting_bpm: Int? = null,
        /**
         * Dose-facing sensitivity before the stress floor, mg/dL per U.
         *
         * `var`, and set late: the value only exists once the working sensitivity is finalised,
         * thousands of lines after this object is built at tick bootstrap. Without these three fields
         * no support package can say whether the wiring works — `variable_sens_mgdl` is read after
         * every multiplier and cannot show what the floor moved.
         */
        var stress_floor_isf_before_mgdl: Double? = null,
        /** Dose-facing sensitivity after the stress floor, mg/dL per U. */
        var stress_floor_isf_after_mgdl: Double? = null,
        /** True when the floor really raised the dose-facing sensitivity this tick. */
        var stress_floor_raised_isf: Boolean? = null,
        /** Shadow: sensitivity an unconditional exit clamp relative to the profile would command. */
        val isf_profile_relative_shadow_mgdl: Double? = null,
        /** Shadow: true when that clamp would have changed the value. */
        val isf_profile_relative_bound_hit: Boolean? = null,
        /** Shadow: sensitivity ratio measured from outcomes, dimensionless, 1.0 = the profile is right. */
        val sensitivity_ratio_r: Double? = null,
        /** Shadow: sensitivity this ratio would command, i.e. profile x ratio, bounded. */
        val isf_shadow_s_mgdl: Double? = null,
        /** Shadow: how many closed windows have been folded in so far. */
        val sensitivity_observations: Int? = null,
        /**
         * Hyper-trajectory Ra floor for this tick, in mg/dL/min, or null when no floor applied.
         *
         * `var`, and set late: the floor is computed inside the engaged Autodrive branch, thousands of
         * lines after this object is built at tick bootstrap. As a `val` read at construction it
         * exported `null` on every tick, for ever — the one instrument added to make the floor
         * measurable could not measure it. Written through [markHtrRaFloorForExport].
         */
        var htr_ra_floor_mgdl_per_min: Double? = null,
        /**
         * Ra the controller actually used this tick, in mg/dL/min.
         *
         * Same reason for being a `var`: read at bootstrap it carried the **previous** tick's estimate,
         * which makes the comparison with [htr_ra_floor_mgdl_per_min] meaningless — that comparison is
         * the entire point of exporting the two side by side.
         */
        var estimated_ra_used_mgdl_per_min: Double? = null,
        /** Diagnostic: how many times the meal filter advanced, and how many calls were replays. */
        var ra_estimator_advances: Long? = null,
        var ra_estimator_replayed_calls: Long? = null,
        /**
         * Shadow: Ra the filter would report with its insulin term aligned on the controller's.
         *
         * Never dosed on. Answers whether aligning `InsulinActionModel.ESTIMATOR_TAU_MIN` would pin Ra
         * above the 0.6 / 0.7 / 0.8 gates, which is the one thing the medians could not settle.
         */
        var ra_aligned_tau_shadow_mgdl_per_min: Double? = null,
        /**
         * Writes to the SMB refused after `finalizeAndCapSMB` sealed the tick, and their total size.
         *
         * A non-zero count means a component tried to raise the dose past the terminal. That is the
         * signal `AiAuditor` and the legacy meal paths never produced before the seal existed.
         */
        var smb_seal_refused_count: Int? = null,
        var smb_seal_refused_total_u: Double? = null,
        /** Post-seal raises allowed because the owner is a user-initiated action (meal advisor). */
        var smb_seal_allowed_raise_count: Int? = null,
        /** Coefficient the barrier used, and what it would be with the floor removed. */
        var cbf_coefficient_used: Double? = null,
        var cbf_coefficient_unfloored: Double? = null,
        /** Shadow: insulin the barrier permitted, floored vs unfloored, in U per 5 min. */
        var cbf_permitted_u: Double? = null,
        var cbf_permitted_unfloored_u: Double? = null,
        /** Profile ISF the barrier was handed, so the two above are interpretable. */
        var cbf_profile_isf_mgdl: Double? = null,
        /**
         * Whether the Autodrive gate let the MPC run this tick.
         *
         * Everything the barrier exports only exists on engaged ticks. Without this the disengaged
         * ticks are a blank, and a blank reads as "nothing happened" rather than "the gate was shut".
         *
         * Observation only. Nothing dose-facing reads these three fields.
         */
        var autodrive_gate_engaged: Boolean? = null,
        /** Stable token for why the gate opened or stayed shut, for counting. */
        var autodrive_gate_kind: String? = null,
        /** The same reason with its live numbers, for reading. */
        var autodrive_gate_reason: String? = null,
        /**
         * Shadow measurement of the rise ceiling guard (`RiseCeilingGuard`).
         *
         * Written on every tick that reaches the universal SMB exit, whether
         * [app.aaps.core.keys.BooleanKey.OApsAIMIRiseCeilingGuard] is on or off. That is the whole
         * point: the thresholds were chosen after seeing the data, so they need a measurement made
         * in advance before the gesture is armed.
         */
        var rise_ceiling_guard_would_block: Boolean? = null,
        /** Reason token plus its live numbers (ticks in a row at the ceiling, rise). */
        var rise_ceiling_guard_reason: String? = null,
        /** How many ticks in a row the bolus has come out at a ceiling, this tick included. */
        var rise_ceiling_guard_repeats: Int? = null,
        /**
         * Bolus the guard would have refused, U.
         *
         * Set only when the verdict is "block", so a tick that did not block leaves the field absent
         * instead of reporting a zero that means nothing.
         */
        var rise_ceiling_guard_withheld_u: Double? = null,
        /**
         * Effort SMB reduction, as actually applied at the universal SMB exit.
         *
         * `_requested` is what the effort belief asked for, `_applied` is what was used after the
         * confirmed-meal floor, and the two unit fields bracket the reduction. Before this existed the
         * multiplier could only be recovered by parsing the narrative, which cost one wrong
         * attribution (see docs/AIMI_NEXT_SESSION.md Part A-quater).
         */
        var effort_smb_factor_requested: Double? = null,
        var effort_smb_factor_applied: Double? = null,
        var effort_smb_before_u: Double? = null,
        var effort_smb_after_u: Double? = null,
        /** True when the confirmed-meal floor raised the multiplier this tick. */
        var effort_smb_floored_by_meal: Boolean? = null,
        /**
         * Aggressive-rise floor budget state. The episode budget is out of the dose path, but its
         * accounting still runs, and its absence from the export is why the 2026-08-10 diagnosis
         * rested on inference.
         */
        var rise_floor_spent_u: Double? = null,
        var rise_floor_minutes_since_contribution: Double? = null,
        /**
         * The `variableSensitivity` member, in mg/dL/U, read late in the tick.
         *
         * This is a **post-dose** read, not the sensitivity the dose used. It is taken in
         * `markEstimatorDiagnosticsForExport`, which runs after `applyEndoAndActivityAdjustments`
         * and `applyIsfBoundsAndPhysioMultipliersAfterEndoActivity` have already multiplied
         * `variableSensitivity` by the endo, activity and physiological factors, and long after the
         * MPC and the tube advisor sized the dose.
         *
         * The number the delivered dose used is the fused PKPD sensitivity, exported as
         * `adjustments.intelligence_snapshot_v1.isf.fused_mgdl_per_u`. It is what the MPC reads as
         * `estimatedSI` and what the tube advisor reports as
         * `adjustments.tube_advisor.isf_used_mgdl_per_u`. On the 2026-08-15 package the two agreed
         * on 142 of 160 ticks, while this field agreed with the tube on 0 of 160.
         *
         * Do not compare this field with anything dose-related. An earlier note here claimed a 0.70
         * to 2.65 ratio against [command_isf_mgdl]; that was obtained by inverting the tube's kappa,
         * which cannot be inverted below about 29.7 mg/dL/U because the curve saturates there. The
         * direct instruments contradict it.
         */
        var variable_sens_mgdl: Double? = null,
    )
    data class Adjustments(
        var dynamic_isf: DynamicIsf? = null,
        var basal_safety_cap: BasalCap? = null,
        var physiological_context: PhysioContext? = null,
        /** IOB surveillance / anti-stacking snapshot for AIMI_Decisions.jsonl analysis */
        var iob_surveillance: IobSurveillanceExport? = null,
        /** Effective-IOB release (maxIOB gate) decision snapshot for AIMI_Decisions.jsonl analysis */
        var iob_release: IobReleaseExport? = null,
        /** LGS / predictive hypo safety snapshot (Phase 5 export) */
        var safety_risk: SafetyRiskExport? = null,
        /** Dual scenario curves (CLINICAL_FLOOR + SCENARIO_BEST) */
        var scenario_projection: ScenarioProjectionExport? = null,
        /** Hyper Trajectory Release (projection → SMB floor) */
        var hyper_trajectory_release: HyperTrajectoryReleaseExport? = null,
        /** Physiological phase + behavioral risk policy (HTR / MPC / scenario) */
        var physiological_phase: PhysiologicalPhaseExport? = null,
        /** Multi-label body-state pattern catalog (RBT meta + HTR caps) */
        var physiological_patterns: JsonObject? = null,
        /** Unified meal absorption belief + phase (IOB / HTR / SMB priority) */
        var meal_absorption_phase: MealAbsorptionPhaseExport? = null,
        /** PKPD vs scenario eventual-BG divergence audit (PredictionDivergenceAuditor) */
        var pred_divergence: JsonObject? = null,
        /** Recursive Belief Tree — full JSON object for AIMI_Decisions.jsonl */
        var recursive_belief: JsonObject? = null,
        /** Progressive RBT authority gate decision for shadow -> soft -> hard transitions. */
        var recursive_authority_gate: JsonObject? = null,
        /** Replay-oriented quality bridge built from existing guards and shadow channels. */
        var replay_quality: JsonObject? = null,
        /** Diagnostic-only ordered SMB cap chain; never consumed by dose calculation. */
        var smb_binding_trace: JsonObject? = null,
        /** Shared latent physiological state reused across engines for the tick. */
        var physio_latent_state: JsonObject? = null,
        /** Multi-hypothesis UAM interpretation for meal vs endogenous vs stress vs rebound. */
        var uam_hypotheses: JsonObject? = null,
        /** Unified patient-state snapshot bridging physiology, meal state and user intent. */
        var patient_state: JsonObject? = null,
        /** High-level patient mode and strategy derived from the shared state. */
        var patient_mode: JsonObject? = null,
        /** Trajectory bridge snapshot (telemetry, written on every tick). */
        var traj_bridge: JsonObject? = null,
        /** AIMI Harmonia physiological tree — deploys insulin intent; Harmonia arbitrates dose. */
        var physiological_tree: JsonObject? = null,
        /** Lot A endocrine belief (WCycle + hypo dampen) — context for tree/Harmonia/forensics. */
        var endocrine_belief: JsonObject? = null,
        /** Cascade meal language (Tree→Harmonia→Auditor) — meal_certainty_v1. */
        var meal_certainty: JsonObject? = null,
        /** Cascade D4 / C1 — single dose-facing eventual + minPred for the tick. */
        var dose_terminal_snapshot: JsonObject? = null,
        /**
         * Straight-line tube decision that set this tick's SMB ceiling.
         *
         * `dose_terminal_snapshot` is republished at `late_pkpd`, after the tube has already frozen
         * the caps, so the exported snapshot is **not** the input the tube used. This block carries
         * the inputs of the stage that actually decided, so the two can be compared.
         */
        var tube_advisor: JsonObject? = null,
        /** Wave4 H3 — soft-floor/EGP path-min (production curves + study JSON raw/soft). */
        var pkpd_soft_floor: JsonObject? = null,
        /**
         * What the MPC asked for before the control barrier, and the barrier's own terms.
         *
         * Separates "the solver wanted nothing" from "the barrier suspended everything", which
         * `model_output_u` alone cannot do because it is read after the barrier.
         */
        var control_barrier: JsonObject? = null,
        /** Lot 2 — invariants terminaux du canal basal: taux avant/apres et invariant liant. */
        var basal_terminal: JsonObject? = null,
        /**
         * Universal Adaptive Basal scaling for this tick: heuristic, learned head, and the blend.
         *
         * Carries `n_raw`, the learned value BEFORE the runtime clamp. Only the blended result used to
         * be exported (as free text in the narrative), so a model stuck on one constant and a model
         * that had really learned the same number were impossible to tell apart in a log.
         */
        var adaptive_basal: JsonObject? = null,
        /** AIMI Harmonia simulated production branch; virtual only, never applied to the real pump. */
        var harmonia_simulation: JsonObject? = null,
        /** AIMI Harmonia production owner state; basal-first only and safety-gated. */
        var harmonia_production: JsonObject? = null,
        /** Harmonia SMB authority arbitration (ACCEPT / LIFT_WITHIN_ENVELOPE / REDUCE). */
        var harmonia_smb_authority: JsonObject? = null,
        /** Unified intelligence snapshot (kinetics, causal, ISF, predictions) — intelligence_snapshot_v1. */
        var intelligence_snapshot: JsonObject? = null,
        /** Runtime ownership of T3C for this tick: native RBT, legacy fallback, or safety terminal. */
        var t3c_runtime_ownership: T3cRuntimeOwnershipExport? = null,
        /** Loop vs auditor binding for this tick (sync disposition; follow-up may arrive async). */
        var auditor_tick: JsonObject? = null,
        /**
         * ISF and target of this tick at every level, plus the state of the auditor profile-factor
         * key. Written on every tick, key on or off. Observation only; no dose reads it.
         */
        var auditor_profile_factors: JsonObject? = null,
        /**
         * Post-hypo delivery authority for this tick: whether it applied, which condition declined
         * it, and the SMB before / after its cap. See `docs/adr/0006-autodrive-consumes-authority.md`.
         */
        var post_hypo_delivery: JsonObject? = null,
        /**
         * Running share of the delivered insulin the model actually asked for, against the share the
         * floors added. Observation only; no dose reads it. See `InsulinOriginMeter`.
         */
        var insulin_origin: JsonObject? = null,
    )

    data class T3cRuntimeOwnershipExport(
        val mode: String,
        val native_owner_active: Boolean,
        val legacy_fallback_allowed: Boolean,
        val reason: String,
    )

    data class MealAbsorptionPhaseExport(
        val phase: String,
        val belief: Double,
        val reason: String,
        val memory_active: Boolean,
        val wave_count: Int,
        val meal_delivery_priority: Boolean,
        val chrono_prior: Double,
        val kinetic_score: Double,
        val trajectory_score: Double,
        val physio_score: Double,
    )

    data class PhysiologicalPhaseExport(
        val phase: String,
        val confidence: Double,
        val behavioral_risk: String,
        val reason: String,
        val extended_dawn_guard: Boolean,
        val scenario_best_capped: Boolean,
        val max_htr_tier: String,
        val smb_floor_cap_u: Double?,
        val physio_smb_factor_fused: Double?,
        val physio_phase_source: String?,
    )

    data class HyperTrajectoryReleaseExport(
        val active: Boolean,
        val tier: String,
        val dev_above_target_mgdl: Double,
        val projected_dev_mgdl: Double,
        val severity_weight: Double,
        val absorption_offset_mgdl: Double,
        val smb_floor_u: Double,
        val v3_smb_before_u: Double,
        val v3_smb_after_u: Double,
        val suppress_traj_basal_shift: Boolean,
        val hypo_min_pred_ignored: Boolean,
        val reason: String,
    )

    data class SafetyRiskExport(
        val phase: String,
        val predictive_hypo_suppressed: Boolean,
        val safety_gate: String,
        val halt_remaining_pipeline: Boolean,
        val meal_context_active: Boolean,
        val meal_rise_confirmed: Boolean,
        val composite_min_mgdl: Double,
        val pred_bg_mgdl: Double,
        val eventual_bg_mgdl: Double,
        val uam_terminal_mgdl: Double?,
        val hypo_threshold_mgdl: Double,
        val decision_composite_min_mgdl: Double?,
        val decision_hypo_threshold_mgdl: Double?,
        val reconcile_delta_mgdl: Double?,
    )

    data class ScenarioProjectionExport(
        val floor_terminal_mgdl: Double,
        val floor_path_min_mgdl: Double,
        val best_terminal_mgdl: Double,
        val best_path_min_mgdl: Double,
        /** Pre meal-absorption-lift path-min used by Clamp / DoseTerminal gates. */
        val best_gate_path_min_mgdl: Double,
        val best_gate_path_min_hit_floor: Boolean,
        val terminal_gap_mgdl: Double,
        val trajectory_type: String?,
        val contributors: List<String>,
    )

    /**
     * Effective-IOB release decision (maxIOB gate) for external analytics. `gate_flips_block_to_allow` marks the
     * safety-relevant event: the ledger IOB would block production but the hypo-governed release lets it through.
     */
    data class IobReleaseExport(
        val enabled: Boolean,
        val theta: Double,
        val iob_ledger_u: Double,
        val iob_effective_u: Double?,
        val iob_for_gate_u: Double,
        val released_u: Double,
        val gate_flips_block_to_allow: Boolean,
        val reason: String,
    )

    /**
     * One row-friendly snapshot for external analytics (plateau + high IOB + predicted drop).
     */
    data class DynamicIsf(
        var final_value_mgdl: Double = 0.0,
        val modifiers: MutableList<Modifier> = mutableListOf()
    )
    data class Modifier(val source: String, val factor: Double, val clinical_reason: String)
    data class BasalCap(val status: String, val limit_uph: Double, val safety_reason: String)
    data class PhysioContext(val hormonal_cycle_phase: String, val physical_activity_mode: String)
    data class Outcome(val clinical_decision: String, val dosage_u: Double, val target_basal_uph: Double? = null, val narrative_explanation: String)

    fun toMedicalJson(): String {
        return try {
            val json = JsonObj()
            json.put("event_id", event_id)
            json.put("timestamp", timestamp)
            json.put("trigger", trigger)

            val base = JsonObj()
            base.put("profile_isf_mgdl", baseline_state.profile_isf_mgdl)
            base.put("profile_basal_uph", baseline_state.profile_basal_uph)
            base.put("current_bg_mgdl", baseline_state.current_bg_mgdl)
            base.put("cob_g", baseline_state.cob_g)
            base.put("iob_u", baseline_state.iob_u)
            base.put("profile_isf_static_mgdl", baseline_state.profile_isf_static_mgdl ?: AimiJson.NULL)
            base.put("command_isf_mgdl", baseline_state.command_isf_mgdl ?: AimiJson.NULL)
            base.put("isf_source", baseline_state.isf_source ?: AimiJson.NULL)
            base.put("isf_age_ms", baseline_state.isf_age_ms ?: AimiJson.NULL)
            base.put("isf_cache_key", baseline_state.isf_cache_key ?: AimiJson.NULL)
            base.put("isf_calc_path", baseline_state.isf_calc_path ?: AimiJson.NULL)
            base.put("isf_cache_size", baseline_state.isf_cache_size ?: AimiJson.NULL)
            base.put("isf_cache_glucose_mgdl", baseline_state.isf_cache_glucose_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_median_mgdl", baseline_state.isf_obs_median_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_night_median_mgdl", baseline_state.isf_obs_night_median_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_day_median_mgdl", baseline_state.isf_obs_day_median_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_window_count", baseline_state.isf_obs_window_count ?: AimiJson.NULL)
            base.put("isf_obs_night_count", baseline_state.isf_obs_night_count ?: AimiJson.NULL)
            base.put("isf_obs_day_count", baseline_state.isf_obs_day_count ?: AimiJson.NULL)
            base.put("isf_obs_last_window_end_ms", baseline_state.isf_obs_last_window_end_ms ?: AimiJson.NULL)
            base.put("isf_obs_last_window_mgdl", baseline_state.isf_obs_last_window_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_last_window_drop_mgdl", baseline_state.isf_obs_last_window_drop_mgdl ?: AimiJson.NULL)
            base.put("isf_obs_last_window_absorbed_u", baseline_state.isf_obs_last_window_absorbed_u ?: AimiJson.NULL)
            base.put("effort_smb_armed", baseline_state.effort_smb_armed ?: AimiJson.NULL)
            base.put("meal_boost_requested_uph", baseline_state.meal_boost_requested_uph ?: AimiJson.NULL)
            base.put("meal_boost_cap_tier", baseline_state.meal_boost_cap_tier ?: AimiJson.NULL)
            base.put("meal_boost_cap_max_uph", baseline_state.meal_boost_cap_max_uph ?: AimiJson.NULL)
            base.put("meal_boost_capped_uph", baseline_state.meal_boost_capped_uph ?: AimiJson.NULL)
            base.put("meal_boost_cap_would_bind", baseline_state.meal_boost_cap_would_bind ?: AimiJson.NULL)
            base.put("engine_rate_uph", baseline_state.engine_rate_uph ?: AimiJson.NULL)
            base.put("rt_rate_uph", baseline_state.rt_rate_uph ?: AimiJson.NULL)
            base.put("merge_mode", baseline_state.merge_mode ?: AimiJson.NULL)
            base.put("merge_winner", baseline_state.merge_winner ?: AimiJson.NULL)
            base.put("late_fat_damping_window", baseline_state.late_fat_damping_window ?: AimiJson.NULL)
            base.put("late_fat_rise_flag", baseline_state.late_fat_rise_flag ?: AimiJson.NULL)
            base.put("late_fat_onset_age_min", baseline_state.late_fat_onset_age_min ?: AimiJson.NULL)
            base.put("harmonia_cf_rule", baseline_state.harmonia_cf_rule ?: AimiJson.NULL)
            base.put("harmonia_cf_action", baseline_state.harmonia_cf_action ?: AimiJson.NULL)
            base.put("harmonia_cf_basal_uph", baseline_state.harmonia_cf_basal_uph ?: AimiJson.NULL)
            base.put("harmonia_cf_changes_proposal", baseline_state.harmonia_cf_changes_proposal ?: AimiJson.NULL)
            base.put("harmonia_block_was_escalation", baseline_state.harmonia_block_was_escalation ?: AimiJson.NULL)
            base.put("harmonia_block_delta_uph", baseline_state.harmonia_block_delta_uph ?: AimiJson.NULL)
            base.put("harmonia_block_stake_u", baseline_state.harmonia_block_stake_u ?: AimiJson.NULL)
            base.put("harmonia_applied_gap_uph", baseline_state.harmonia_applied_gap_uph ?: AimiJson.NULL)
            base.put("harmonia_verdict_critical_safety", baseline_state.harmonia_verdict_critical_safety ?: AimiJson.NULL)
            base.put("harmonia_verdict_context_suppress", baseline_state.harmonia_verdict_context_suppress ?: AimiJson.NULL)
            base.put("harmonia_verdict_meal_mode", baseline_state.harmonia_verdict_meal_mode ?: AimiJson.NULL)
            base.put("harmonia_verdict_known_to_engine", baseline_state.harmonia_verdict_known_to_engine ?: AimiJson.NULL)
            base.put("harmonia_prev_blocker", baseline_state.harmonia_prev_blocker ?: AimiJson.NULL)
            base.put("harmonia_prev_blocked_streak", baseline_state.harmonia_prev_blocked_streak ?: AimiJson.NULL)
            base.put("harmonia_tree_risk_divergence", baseline_state.harmonia_tree_risk_divergence ?: AimiJson.NULL)
            base.put("isf_kalman_fast_mgdl", baseline_state.isf_kalman_fast_mgdl ?: AimiJson.NULL)
            base.put("isf_adj_engine_mgdl", baseline_state.isf_adj_engine_mgdl ?: AimiJson.NULL)
            base.put("isf_fused_slow_mgdl", baseline_state.isf_fused_slow_mgdl ?: AimiJson.NULL)
            base.put("isf_trust_fast", baseline_state.isf_trust_fast ?: AimiJson.NULL)
            base.put("isf_dynamic_factor", baseline_state.isf_dynamic_factor ?: AimiJson.NULL)
            base.put("isf_trajectory_multiplier", baseline_state.isf_trajectory_multiplier ?: AimiJson.NULL)
            base.put("estimated_ra_mgdl_per_min", baseline_state.estimated_ra_mgdl_per_min ?: AimiJson.NULL)
            base.put("physio_isf_factor", baseline_state.physio_isf_factor ?: AimiJson.NULL)
            base.put("isf_pre_floor_mgdl", baseline_state.isf_pre_floor_mgdl ?: AimiJson.NULL)
            base.put("stress_isf_floor_active", baseline_state.stress_isf_floor_active ?: AimiJson.NULL)
            base.put("stress_isf_floor_reason", baseline_state.stress_isf_floor_reason ?: AimiJson.NULL)
            base.put("stress_isf_floor_isf_mgdl", baseline_state.stress_isf_floor_isf_mgdl ?: AimiJson.NULL)
            base.put("stress_floor_awake_resting_bpm", baseline_state.stress_floor_awake_resting_bpm ?: AimiJson.NULL)
            base.put("stress_floor_isf_before_mgdl", baseline_state.stress_floor_isf_before_mgdl ?: AimiJson.NULL)
            base.put("stress_floor_isf_after_mgdl", baseline_state.stress_floor_isf_after_mgdl ?: AimiJson.NULL)
            base.put("stress_floor_raised_isf", baseline_state.stress_floor_raised_isf ?: AimiJson.NULL)
            base.put("isf_profile_relative_shadow_mgdl", baseline_state.isf_profile_relative_shadow_mgdl ?: AimiJson.NULL)
            base.put("isf_profile_relative_bound_hit", baseline_state.isf_profile_relative_bound_hit ?: AimiJson.NULL)
            base.put("sensitivity_ratio_r", baseline_state.sensitivity_ratio_r ?: AimiJson.NULL)
            base.put("isf_shadow_s_mgdl", baseline_state.isf_shadow_s_mgdl ?: AimiJson.NULL)
            base.put("sensitivity_observations", baseline_state.sensitivity_observations ?: AimiJson.NULL)
            base.put("htr_ra_floor_mgdl_per_min", baseline_state.htr_ra_floor_mgdl_per_min ?: AimiJson.NULL)
            base.put("estimated_ra_used_mgdl_per_min", baseline_state.estimated_ra_used_mgdl_per_min ?: AimiJson.NULL)
            base.put("ra_estimator_advances", baseline_state.ra_estimator_advances ?: AimiJson.NULL)
            base.put("ra_estimator_replayed_calls", baseline_state.ra_estimator_replayed_calls ?: AimiJson.NULL)
            base.put("ra_aligned_tau_shadow_mgdl_per_min", baseline_state.ra_aligned_tau_shadow_mgdl_per_min ?: AimiJson.NULL)
            base.put("smb_seal_refused_count", baseline_state.smb_seal_refused_count ?: AimiJson.NULL)
            base.put("smb_seal_refused_total_u", baseline_state.smb_seal_refused_total_u ?: AimiJson.NULL)
            base.put("smb_seal_allowed_raise_count", baseline_state.smb_seal_allowed_raise_count ?: AimiJson.NULL)
            base.put("cbf_coefficient_used", baseline_state.cbf_coefficient_used ?: AimiJson.NULL)
            base.put("cbf_coefficient_unfloored", baseline_state.cbf_coefficient_unfloored ?: AimiJson.NULL)
            base.put("cbf_permitted_u", baseline_state.cbf_permitted_u ?: AimiJson.NULL)
            base.put("cbf_permitted_unfloored_u", baseline_state.cbf_permitted_unfloored_u ?: AimiJson.NULL)
            base.put("cbf_profile_isf_mgdl", baseline_state.cbf_profile_isf_mgdl ?: AimiJson.NULL)
            base.put("autodrive_gate_engaged", baseline_state.autodrive_gate_engaged ?: AimiJson.NULL)
            base.put("autodrive_gate_kind", baseline_state.autodrive_gate_kind ?: AimiJson.NULL)
            base.put("autodrive_gate_reason", baseline_state.autodrive_gate_reason ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_would_block", baseline_state.rise_ceiling_guard_would_block ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_reason", baseline_state.rise_ceiling_guard_reason ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_repeats", baseline_state.rise_ceiling_guard_repeats ?: AimiJson.NULL)
            base.put("rise_ceiling_guard_withheld_u", baseline_state.rise_ceiling_guard_withheld_u ?: AimiJson.NULL)
            base.put("effort_smb_factor_requested", baseline_state.effort_smb_factor_requested ?: AimiJson.NULL)
            base.put("effort_smb_factor_applied", baseline_state.effort_smb_factor_applied ?: AimiJson.NULL)
            base.put("effort_smb_before_u", baseline_state.effort_smb_before_u ?: AimiJson.NULL)
            base.put("effort_smb_after_u", baseline_state.effort_smb_after_u ?: AimiJson.NULL)
            base.put("effort_smb_floored_by_meal", baseline_state.effort_smb_floored_by_meal ?: AimiJson.NULL)
            base.put("rise_floor_spent_u", baseline_state.rise_floor_spent_u ?: AimiJson.NULL)
            base.put("variable_sens_mgdl", baseline_state.variable_sens_mgdl ?: AimiJson.NULL)
            base.put(
                "rise_floor_minutes_since_contribution",
                baseline_state.rise_floor_minutes_since_contribution ?: AimiJson.NULL,
            )
            json.put("baseline_state", base)

            val adj = JsonObj()
            adjustments.dynamic_isf?.let { d ->
                val dJson = JsonObj()
                dJson.put("final_value_mgdl", d.final_value_mgdl)
                val modsIdx = JsonArr()
                d.modifiers.forEach { m ->
                    val mJson = JsonObj()
                    mJson.put("source", m.source)
                    mJson.put("factor", m.factor)
                    mJson.put("reason", m.clinical_reason)
                    modsIdx.put(mJson)
                }
                dJson.put("modifiers", modsIdx)
                adj.put("dynamic_isf", dJson)
            }
            adjustments.physiological_context?.let { p ->
                val pJson = JsonObj()
                pJson.put("cycle_phase", p.hormonal_cycle_phase)
                pJson.put("activity_mode", p.physical_activity_mode)
                adj.put("physio_context", pJson)
            }
            // Add Basal Cap if present
            adjustments.basal_safety_cap?.let { c ->
                val cJson = JsonObj()
                cJson.put("status", c.status)
                cJson.put("limit_uph", c.limit_uph)
                cJson.put("reason", c.safety_reason)
                adj.put("basal_cap", cJson)
            }
            adjustments.iob_surveillance?.let { s ->
                val sJson = JsonObj()
                sJson.put("pref_enabled", s.pref_enabled)
                sJson.put("preference_key", s.preference_key)
                sJson.put("kind", s.kind)
                sJson.put("active_reason", s.active_reason ?: AimiJson.NULL)
                sJson.put("meal_priority_context", s.meal_priority_context)
                sJson.put("bg_mgdl", s.bg_mgdl)
                sJson.put("target_bg_mgdl", s.target_bg_mgdl)
                sJson.put("delta_mgdl_5m", s.delta_mgdl_5m)
                sJson.put("short_avg_delta_mgdl_5m", s.short_avg_delta_mgdl_5m)
                sJson.put("iob_u", s.iob_u)
                sJson.put("max_iob_u", s.max_iob_u)
                sJson.put("iob_floor_u", s.iob_floor_u)
                sJson.put("eventual_bg", s.eventual_bg ?: AimiJson.NULL)
                sJson.put("min_predicted_bg", s.min_predicted_bg ?: AimiJson.NULL)
                sJson.put("trajectory_energy", s.trajectory_energy ?: AimiJson.NULL)
                sJson.put("signal_eventual_drop", s.signal_eventual_drop)
                sJson.put("signal_min_pred_drop", s.signal_min_pred_drop)
                sJson.put("signal_trajectory_stack", s.signal_trajectory_stack)
                sJson.put("smb_multiplier", s.smb_multiplier)
                sJson.put("smb_cap_u", s.smb_cap_u)
                sJson.put("suppress_red_carpet_restore", s.suppress_red_carpet_restore)
                sJson.put("tbr_boost_floor", s.tbr_boost_floor)
                sJson.put("smb_u_after_pkpd_before_stacking", s.smb_u_after_pkpd_before_stacking)
                sJson.put("smb_u_after_stacking_step", s.smb_u_after_stacking_step)
                sJson.put("stacking_reduced_smb", s.stacking_reduced_smb)
                sJson.put("pkpd_tbr_boost_after_finalize", s.pkpd_tbr_boost_after_finalize)
                sJson.put("smb_u_after_cap_smb_dose", s.smb_u_after_cap_smb_dose)
                sJson.put("smb_u_final_for_delivery", s.smb_u_final_for_delivery)
                sJson.put("smb_final_source", s.smb_final_source)
                sJson.put("summary_line", s.summary_line)
                sJson.put("tuning_reference", s.tuning_reference)
                adj.put("iob_surveillance", sJson)
            }
            adjustments.iob_release?.let { r ->
                val rJson = JsonObj()
                rJson.put("enabled", r.enabled)
                rJson.put("theta", r.theta)
                rJson.put("iob_ledger_u", r.iob_ledger_u)
                rJson.put("iob_effective_u", r.iob_effective_u ?: AimiJson.NULL)
                rJson.put("iob_for_gate_u", r.iob_for_gate_u)
                rJson.put("released_u", r.released_u)
                rJson.put("gate_flips_block_to_allow", r.gate_flips_block_to_allow)
                rJson.put("reason", r.reason)
                adj.put("iob_release", rJson)
            }
            adjustments.safety_risk?.let { r ->
                val rJson = JsonObj()
                rJson.put("phase", r.phase)
                rJson.put("predictive_hypo_suppressed", r.predictive_hypo_suppressed)
                rJson.put("safety_gate", r.safety_gate)
                rJson.put("halt_remaining_pipeline", r.halt_remaining_pipeline)
                rJson.put("meal_context_active", r.meal_context_active)
                rJson.put("meal_rise_confirmed", r.meal_rise_confirmed)
                rJson.put("composite_min_mgdl", r.composite_min_mgdl)
                rJson.put("pred_bg_mgdl", r.pred_bg_mgdl)
                rJson.put("eventual_bg_mgdl", r.eventual_bg_mgdl)
                rJson.put("uam_terminal_mgdl", r.uam_terminal_mgdl ?: AimiJson.NULL)
                rJson.put("hypo_threshold_mgdl", r.hypo_threshold_mgdl)
                rJson.put("decision_composite_min_mgdl", r.decision_composite_min_mgdl ?: AimiJson.NULL)
                rJson.put("decision_hypo_threshold_mgdl", r.decision_hypo_threshold_mgdl ?: AimiJson.NULL)
                rJson.put("reconcile_delta_mgdl", r.reconcile_delta_mgdl ?: AimiJson.NULL)
                adj.put("safety_risk", rJson)
            }
            adjustments.scenario_projection?.let { s ->
                val sJson = JsonObj()
                sJson.put("floor_terminal_mgdl", s.floor_terminal_mgdl)
                sJson.put("floor_path_min_mgdl", s.floor_path_min_mgdl)
                sJson.put("best_terminal_mgdl", s.best_terminal_mgdl)
                sJson.put("best_path_min_mgdl", s.best_path_min_mgdl)
                sJson.put("best_gate_path_min_mgdl", s.best_gate_path_min_mgdl)
                sJson.put("best_gate_path_min_hit_floor", s.best_gate_path_min_hit_floor)
                sJson.put("terminal_gap_mgdl", s.terminal_gap_mgdl)
                sJson.put("trajectory_type", s.trajectory_type ?: AimiJson.NULL)
                sJson.put("contributors", JsonArr(s.contributors))
                adj.put("scenario_projection", sJson)
            }
            adjustments.hyper_trajectory_release?.let { h ->
                val hJson = JsonObj()
                hJson.put("active", h.active)
                hJson.put("tier", h.tier)
                hJson.put("dev_above_target_mgdl", h.dev_above_target_mgdl)
                hJson.put("projected_dev_mgdl", h.projected_dev_mgdl)
                hJson.put("severity_weight", h.severity_weight)
                hJson.put("absorption_offset_mgdl", h.absorption_offset_mgdl)
                hJson.put("smb_floor_u", h.smb_floor_u)
                hJson.put("v3_smb_before_u", h.v3_smb_before_u)
                hJson.put("v3_smb_after_u", h.v3_smb_after_u)
                hJson.put("suppress_traj_basal_shift", h.suppress_traj_basal_shift)
                hJson.put("hypo_min_pred_ignored", h.hypo_min_pred_ignored)
                hJson.put("reason", h.reason)
                adj.put("hyper_trajectory_release", hJson)
            }
            adjustments.physiological_phase?.let { p ->
                val pJson = JsonObj()
                pJson.put("phase", p.phase)
                pJson.put("confidence", p.confidence)
                pJson.put("behavioral_risk", p.behavioral_risk)
                pJson.put("reason", p.reason)
                pJson.put("extended_dawn_guard", p.extended_dawn_guard)
                pJson.put("scenario_best_capped", p.scenario_best_capped)
                pJson.put("max_htr_tier", p.max_htr_tier)
                pJson.put("smb_floor_cap_u", p.smb_floor_cap_u ?: AimiJson.NULL)
                pJson.put("physio_smb_factor_fused", p.physio_smb_factor_fused ?: AimiJson.NULL)
                pJson.put("physio_phase_source", p.physio_phase_source ?: AimiJson.NULL)
                adj.put("physiological_phase", pJson)
            }
            adjustments.physiological_patterns?.let { pp ->
                adj.put("physiological_patterns", pp)
            }
            adjustments.meal_absorption_phase?.let { m ->
                val mJson = JsonObj()
                mJson.put("phase", m.phase)
                mJson.put("belief", m.belief)
                mJson.put("reason", m.reason)
                mJson.put("memory_active", m.memory_active)
                mJson.put("wave_count", m.wave_count)
                mJson.put("meal_delivery_priority", m.meal_delivery_priority)
                mJson.put("chrono_prior", m.chrono_prior)
                mJson.put("kinetic_score", m.kinetic_score)
                mJson.put("trajectory_score", m.trajectory_score)
                mJson.put("physio_score", m.physio_score)
                adj.put("meal_absorption_phase", mJson)
            }
            adjustments.pred_divergence?.let { pd ->
                adj.put("pred_divergence", pd)
            }
            adjustments.recursive_belief?.let { rb ->
                adj.put("recursive_belief", rb)
            }
            adjustments.recursive_authority_gate?.let { rag ->
                adj.put("recursive_authority_gate", rag)
            }
            adjustments.replay_quality?.let { rq ->
                adj.put("replay_quality", rq)
            }
            adjustments.smb_binding_trace?.let { trace ->
                adj.put("smb_binding_trace", trace)
            }
            adjustments.physio_latent_state?.let { latent ->
                adj.put("physio_latent_state", latent)
            }
            adjustments.uam_hypotheses?.let { hypotheses ->
                adj.put("uam_hypotheses", hypotheses)
            }
            adjustments.patient_state?.let { patientState ->
                adj.put("patient_state", patientState)
            }
            adjustments.patient_mode?.let { patientMode ->
                adj.put("patient_mode", patientMode)
            }
            adjustments.traj_bridge?.let { bridge ->
                adj.put("traj_bridge", bridge)
            }
            adjustments.endocrine_belief?.let { endocrine ->
                adj.put("endocrine_belief", endocrine)
            }
            adjustments.physiological_tree?.let { tree ->
                adj.put("physiological_tree", tree)
            }
            adjustments.meal_certainty?.let { mealCertainty ->
                adj.put("meal_certainty", mealCertainty)
            }
            adjustments.dose_terminal_snapshot?.let { doseTerminal ->
                adj.put("dose_terminal_snapshot", doseTerminal)
            }
            adjustments.control_barrier?.let { cb ->
                adj.put("control_barrier", cb)
            }
            adjustments.tube_advisor?.let { tube ->
                adj.put("tube_advisor", tube)
            }
            adjustments.pkpd_soft_floor?.let { softFloor ->
                adj.put("pkpd_soft_floor", softFloor)
            }
            adjustments.basal_terminal?.let { terminal ->
                adj.put("basal_terminal", terminal)
            }
            adjustments.adaptive_basal?.let { adaptiveBasal ->
                adj.put("adaptive_basal", adaptiveBasal)
            }
            adjustments.harmonia_simulation?.let { simulation ->
                adj.put("harmonia_simulation", simulation)
            }
            adjustments.harmonia_production?.let { production ->
                adj.put("harmonia_production", production)
            }
            adjustments.harmonia_smb_authority?.let { smbAuthority ->
                adj.put("harmonia_smb_authority", smbAuthority)
            }
            adjustments.intelligence_snapshot?.let { snapshot ->
                adj.put("intelligence_snapshot_v1", snapshot)
            }
            adjustments.t3c_runtime_ownership?.let { ownership ->
                val ownershipJson = JsonObj()
                ownershipJson.put("mode", ownership.mode)
                ownershipJson.put("native_owner_active", ownership.native_owner_active)
                ownershipJson.put("legacy_fallback_allowed", ownership.legacy_fallback_allowed)
                ownershipJson.put("reason", ownership.reason)
                adj.put("t3c_runtime_ownership", ownershipJson)
            }
            adjustments.auditor_tick?.let { auditorTick ->
                adj.put("auditor_tick", auditorTick)
            }
            adjustments.auditor_profile_factors?.let { profileFactors ->
                adj.put("auditor_profile_factors", profileFactors)
            }
            adjustments.post_hypo_delivery?.let { postHypoDelivery ->
                adj.put("post_hypo_delivery", postHypoDelivery)
            }
            adjustments.insulin_origin?.let { insulinOrigin ->
                adj.put("insulin_origin", insulinOrigin)
            }
            json.put("adjustments", adj)

            outcome?.let { o ->
                val oJson = JsonObj()
                oJson.put("decision", o.clinical_decision)
                oJson.put("amount", o.dosage_u)
                o.target_basal_uph?.let { oJson.put("target_basal_rate_uph", it) }
                oJson.put("narrative", o.narrative_explanation)
                json.put("outcome", oJson)
            }
            json.toString()
        } catch (_: Exception) { "{ \"error\": \"JSON Generation Failed\" }" }
    }
}
