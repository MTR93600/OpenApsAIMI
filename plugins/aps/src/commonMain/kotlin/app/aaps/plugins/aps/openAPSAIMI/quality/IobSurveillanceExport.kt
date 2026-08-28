package app.aaps.plugins.aps.openAPSAIMI.quality

/**
 * One row-friendly snapshot for external analytics (plateau + high IOB + predicted drop).
 */
data class IobSurveillanceExport(
    val pref_enabled: Boolean,
    val preference_key: String,
    val kind: String,
    val active_reason: String?,
    val meal_priority_context: Boolean,
    val bg_mgdl: Double,
    val target_bg_mgdl: Double,
    val delta_mgdl_5m: Double,
    val short_avg_delta_mgdl_5m: Double,
    val iob_u: Double,
    val max_iob_u: Double,
    val iob_floor_u: Double,
    val eventual_bg: Double?,
    val min_predicted_bg: Double?,
    val trajectory_energy: Double?,
    val signal_eventual_drop: Boolean,
    val signal_min_pred_drop: Boolean,
    val signal_trajectory_stack: Boolean,
    val smb_multiplier: Double,
    val smb_cap_u: Double,
    val suppress_red_carpet_restore: Boolean,
    val tbr_boost_floor: Double,
    val smb_u_after_pkpd_before_stacking: Double,
    val smb_u_after_stacking_step: Double,
    val stacking_reduced_smb: Boolean,
    val pkpd_tbr_boost_after_finalize: Double,
    /** SMB after `capSmbDose` (maxSMB / IOB space), before Red Carpet restore. */
    val smb_u_after_cap_smb_dose: Double,
    /** Dose written to `RT.units` — aligns with pump / enacted SMB for this path. */
    val smb_u_final_for_delivery: Double,
    /** How `smb_u_final_for_delivery` was chosen: standard_safe_cap vs red_carpet. */
    val smb_final_source: String,
    val summary_line: String,
    val tuning_reference: String
)
