package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ISF.CommandedIsf

/**
 * The Android `decisionContextForTrigger` body.
 * Estimator reads stay at the call site: that class is Android-only.
 * [AimiDecisionContext.BaselineState.htr_ra_floor_mgdl_per_min] stays null.
 * The tick writes it later.
 */
internal fun decideAimiDecisionContext(
    eventId: String,
    timestamp: Long,
    trigger: String,
    profileIsfMgdl: Double,
    profileBasalUph: Double,
    currentBgMgdl: Double,
    cobG: Double,
    iobU: Double,
    estimatedRaMgdlPerMin: Double?,
    sensitivityRatioR: Double?,
    isfShadowSMgdl: Double?,
    sensitivityObservations: Int?,
): AimiDecisionContext = AimiDecisionContext(
    event_id = eventId,
    timestamp = timestamp,
    trigger = trigger,
    baseline_state = AimiDecisionContext.BaselineState(
        profile_isf_mgdl = profileIsfMgdl,
        profile_basal_uph = profileBasalUph,
        current_bg_mgdl = currentBgMgdl,
        cob_g = cobG,
        iob_u = iobU,
        profile_isf_static_mgdl = IsfSourceTelemetry.lastProfileStaticMgdl,
        command_isf_mgdl = profileIsfMgdl,
        isf_source = IsfSourceTelemetry.lastSource,
        isf_age_ms = IsfSourceTelemetry.lastAgeMs,
        isf_cache_key = IsfSourceTelemetry.lastCacheKey,
        isf_cache_glucose_mgdl = IsfSourceTelemetry.lastCacheGlucoseMgdl,
        isf_kalman_fast_mgdl = IsfSourceTelemetry.lastKalmanFastIsf,
        isf_adj_engine_mgdl = IsfSourceTelemetry.lastIsfAdjEngine,
        isf_fused_slow_mgdl = IsfSourceTelemetry.lastFusedSlowIsf,
        isf_trust_fast = IsfSourceTelemetry.lastTrustFast,
        isf_dynamic_factor = IsfSourceTelemetry.lastDynamicFactor,
        isf_trajectory_multiplier = IsfSourceTelemetry.lastTrajectoryMultiplier,
        estimated_ra_mgdl_per_min = estimatedRaMgdlPerMin,
        physio_isf_factor = IsfSourceTelemetry.lastPhysioIsfFactor,
        isf_pre_floor_mgdl = CommandedIsf.lastPreFloorMgdlPerU,
        stress_isf_floor_active = IsfSourceTelemetry.lastStressIsfFloorActive,
        stress_isf_floor_reason = IsfSourceTelemetry.lastStressIsfFloorReason,
        stress_isf_floor_isf_mgdl = IsfSourceTelemetry.lastStressIsfFloorIsfMgdl,
        stress_floor_awake_resting_bpm = IsfSourceTelemetry.lastStressIsfFloorAwakeRestingBpm,
        isf_profile_relative_shadow_mgdl = IsfSourceTelemetry.lastProfileRelativeShadowMgdl,
        isf_profile_relative_bound_hit = IsfSourceTelemetry.lastProfileRelativeBoundHit,
        sensitivity_ratio_r = sensitivityRatioR,
        isf_shadow_s_mgdl = isfShadowSMgdl,
        sensitivity_observations = sensitivityObservations,
        htr_ra_floor_mgdl_per_min = null,
    ),
)
