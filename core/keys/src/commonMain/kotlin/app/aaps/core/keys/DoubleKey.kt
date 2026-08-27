package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec
import app.aaps.core.keys.interfaces.TextRef

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val title: TextRef,
    override val summary: TextRef? = null,
    override val preferenceType: PreferenceType = PreferenceType.TEXT_FIELD,
    override val defaultedBySM: Boolean = false,
    override val calculatedBySM: Boolean = false,
    override val showInApsMode: Boolean = true,
    override val showInNsClientMode: Boolean = true,
    override val showInPumpControlMode: Boolean = true,
    override val dependency: BooleanPreferenceKey? = null,
    override val negativeDependency: BooleanPreferenceKey? = null,
    override val hideParentScreenIfHidden: Boolean = false,
    override val exportable: Boolean = true,
    override val unitType: UnitType = UnitType.NONE,
    override val sync: SyncSpec? = null
) : DoublePreferenceKey {

    OverviewInsulinButtonIncrement1(
        key = "insulin_button_increment_1",
        defaultValue = 0.5,
        min = -5.0,
        max = 5.0,
        title = KeysStrings.pref_title_insulin_button_increment_1,
        summary = KeysStrings.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    OverviewInsulinButtonIncrement2(
        key = "insulin_button_increment_2",
        defaultValue = 1.0,
        min = -5.0,
        max = 5.0,
        title = KeysStrings.pref_title_insulin_button_increment_2,
        summary = KeysStrings.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    OverviewInsulinButtonIncrement3(
        key = "insulin_button_increment_3",
        defaultValue = 2.0,
        min = -5.0,
        max = 5.0,
        title = KeysStrings.pref_title_insulin_button_increment_3,
        summary = KeysStrings.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ActionsFillButton1(key = "fill_button1", defaultValue = 0.3, min = 0.05, max = 20.0, title = KeysStrings.pref_title_fill_button_1, defaultedBySM = true, hideParentScreenIfHidden = true, unitType = UnitType.INSULIN),
    ActionsFillButton2(key = "fill_button2", defaultValue = 0.0, min = 0.0, max = 20.0, title = KeysStrings.pref_title_fill_button_2, defaultedBySM = true, unitType = UnitType.INSULIN),
    ActionsFillButton3(key = "fill_button3", defaultValue = 0.0, min = 0.0, max = 20.0, title = KeysStrings.pref_title_fill_button_3, defaultedBySM = true, unitType = UnitType.INSULIN),
    SafetyMaxBolus(key = "treatmentssafety_maxbolus", defaultValue = 3.0, min = 0.1, max = 60.0, title = KeysStrings.pref_title_max_bolus, unitType = UnitType.INSULIN, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsMaxBasal(
        key = "openapsma_max_basal",
        defaultValue = 1.0,
        min = 0.1,
        max = 25.0,
        title = KeysStrings.pref_title_max_basal,
        summary = KeysStrings.openapsma_max_basal_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN_RATE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsSmbMaxIob(
        key = "openapsmb_max_iob",
        defaultValue = 3.0,
        min = 0.0,
        max = 70.0,
        title = KeysStrings.pref_title_smb_max_iob,
        summary = KeysStrings.openapssmb_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaMaxIob(
        key = "openapsma_max_iob",
        defaultValue = 1.5,
        min = 0.0,
        max = 25.0,
        title = KeysStrings.pref_title_ama_max_iob,
        summary = KeysStrings.openapsma_max_iob_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsMaxDailyMultiplier(
        key = "openapsama_max_daily_safety_multiplier",
        defaultValue = 3.0,
        min = 1.0,
        max = 10.0,
        title = KeysStrings.pref_title_max_daily_multiplier,
        summary = KeysStrings.openapsama_max_daily_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsMaxCurrentBasalMultiplier(
        key = "openapsama_current_basal_safety_multiplier",
        defaultValue = 4.0,
        min = 1.0,
        max = 10.0,
        title = KeysStrings.pref_title_current_basal_multiplier,
        summary = KeysStrings.openapsama_current_basal_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaBolusSnoozeDivisor(
        key = "bolussnooze_dia_divisor",
        defaultValue = 2.0,
        min = 1.0,
        max = 10.0,
        title = KeysStrings.pref_title_bolus_snooze_divisor,
        summary = KeysStrings.openapsama_bolus_snooze_dia_divisor_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaMin5MinCarbsImpact(
        key = "openapsama_min_5m_carbimpact",
        defaultValue = 3.0,
        min = 1.0,
        max = 12.0,
        title = KeysStrings.pref_title_ama_min_5m_carbs_impact,
        summary = KeysStrings.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsSmbMin5MinCarbsImpact(
        key = "openaps_smb_min_5m_carbimpact",
        defaultValue = 8.0,
        min = 1.0,
        max = 12.0,
        title = KeysStrings.pref_title_smb_min_5m_carbs_impact,
        summary = KeysStrings.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AbsorptionCutOff(
        key = "absorption_cutoff",
        defaultValue = 6.0,
        min = 4.0,
        max = 10.0,
        title = KeysStrings.pref_title_absorption_cutoff,
        summary = KeysStrings.absorption_cutoff_summary,
        unitType = UnitType.HOURS_DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AbsorptionMaxTime(
        key = "absorption_maxtime",
        defaultValue = 6.0,
        min = 4.0,
        max = 10.0,
        title = KeysStrings.pref_title_absorption_maxtime,
        summary = KeysStrings.absorption_max_time_summary,
        unitType = UnitType.HOURS_DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutosensMin(
        key = "autosens_min",
        defaultValue = 0.7,
        min = 0.1,
        max = 1.0,
        title = KeysStrings.pref_title_autosens_min,
        summary = KeysStrings.openapsama_autosens_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutosensMax(
        key = "autosens_max",
        defaultValue = 1.2,
        min = 0.5,
        max = 3.0,
        title = KeysStrings.pref_title_autosens_max,
        summary = KeysStrings.openapsama_autosens_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfMin(
        key = "autoISF_min",
        defaultValue = 1.0,
        min = 0.3,
        max = 1.0,
        title = KeysStrings.pref_title_autoisf_min,
        summary = KeysStrings.openapsama_autoISF_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfMax(
        key = "autoISF_max",
        defaultValue = 1.0,
        min = 1.0,
        max = 3.0,
        title = KeysStrings.pref_title_autoisf_max,
        summary = KeysStrings.openapsama_autoISF_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfBgAccelWeight(
        key = "bgAccel_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        title = KeysStrings.pref_title_bg_accel_weight,
        summary = KeysStrings.openapsama_bgAccel_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfBgBrakeWeight(
        key = "bgBrake_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        title = KeysStrings.pref_title_bg_brake_weight,
        summary = KeysStrings.openapsama_bgBrake_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLowBgWeight(
        key = "lower_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        title = KeysStrings.pref_title_low_bg_weight,
        summary = KeysStrings.openapsama_lower_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfHighBgWeight(
        key = "higher_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        title = KeysStrings.pref_title_high_bg_weight,
        summary = KeysStrings.openapsama_higher_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioBgRange(
        key = "openapsama_smb_delivery_ratio_bg_range",
        defaultValue = 0.0,
        min = 0.0,
        max = 100.0,
        title = KeysStrings.pref_title_smb_delivery_ratio_bg_range,
        summary = KeysStrings.openapsama_smb_delivery_ratio_bg_range_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfPpWeight(
        key = "pp_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 0.15,
        title = KeysStrings.pref_title_pp_weight,
        summary = KeysStrings.openapsama_pp_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_3,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfDuraWeight(
        key = "dura_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 3.0,
        title = KeysStrings.pref_title_dura_weight,
        summary = KeysStrings.openapsama_dura_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatio(
        key = "openapsama_smb_delivery_ratio",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        title = KeysStrings.pref_title_smb_delivery_ratio,
        summary = KeysStrings.openapsama_smb_delivery_ratio_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioMin(
        key = "openapsama_smb_delivery_ratio_min",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        title = KeysStrings.pref_title_smb_delivery_ratio_min,
        summary = KeysStrings.openapsama_smb_delivery_ratio_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioMax(
        key = "openapsama_smb_delivery_ratio_max",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        title = KeysStrings.pref_title_smb_delivery_ratio_max,
        summary = KeysStrings.openapsama_smb_delivery_ratio_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbMaxRangeExtension(
        key = "openapsama_smb_max_range_extension",
        defaultValue = 1.0,
        min = 1.0,
        max = 5.0,
        title = KeysStrings.pref_title_smb_max_range_extension,
        summary = KeysStrings.openapsama_smb_max_range_extension_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    // AIMI keys ported from freeze aimi-baseline-2026-08-26
    OApsAIMIMaxSMB("key_openapsaimi_max_smb", 1.0, 0.05, 15.0, title = KeysStrings.pref_title_oaps_aimi_max_smb),
    OApsAIMIHighBGMaxSMB("key_openapsaimi_high_bg_max_smb", 1.0, 0.05, 15.0, title = KeysStrings.pref_title_oaps_aimi_high_bg_max_smb),
    OApsAIMIweight("key_aimiweight", 50.0, 1.0, 200.0, title = KeysStrings.pref_title_oaps_aimi_weight),
    /** MPC: max insulin (U) per kg body weight per 5-minute dose search; combined with Max SMB / High BG SMB caps. */
    OApsAIMIMpcInsulinUPerKgPerStep("aimi_mpc_insulin_u_per_kg_per_5min", 0.065, 0.03, 0.12, title = KeysStrings.pref_title_oaps_aimi_mpc_insulin_u_per_kg_per_step),
    OApsAIMICHO("key_cho", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_cho),
    OApsAIMITDD7("key_tdd7", 40.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_tdd7),
    OApsAIMIPkpdInitialDiaH("aimi_pkpd_initial_dia_h", 6.0, 4.0, 24.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_initial_dia_h),
    OApsAIMIPkpdInitialPeakMin("aimi_pkpd_initial_peak_min", 75.0, 35.0, 300.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_initial_peak_min),
    OApsAIMIPkpdAnchorDiaH("aimi_pkpd_anchor_dia_h", 4.0, 4.0, 12.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_anchor_dia_h),
    OApsAIMIPkpdAnchorPeakMin("aimi_pkpd_anchor_peak_min", 75.0, 35.0, 180.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_anchor_peak_min),
    OApsAIMIPkpdBoundsDiaMinH("aimi_pkpd_bounds_dia_min_h", 4.0, 4.0, 24.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_bounds_dia_min_h),
    OApsAIMIPkpdBoundsDiaMaxH("aimi_pkpd_bounds_dia_max_h", 24.0, 6.0, 36.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_bounds_dia_max_h),
    OApsAIMIPkpdBoundsPeakMinMin("aimi_pkpd_bounds_peak_min_min", 30.0, 20.0, 240.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_bounds_peak_min_min),
    OApsAIMIPkpdBoundsPeakMinMax("aimi_pkpd_bounds_peak_min_max", 240.0, 60.0, 480.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_bounds_peak_min_max),
    OApsAIMIPkpdMaxDiaChangePerDayH("aimi_pkpd_max_dia_change_per_day_h", 3.0, 0.1, 6.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_max_dia_change_per_day_h),
    OApsAIMIPkpdMaxPeakChangePerDayMin("aimi_pkpd_max_peak_change_per_day_min", 20.0, 1.0, 60.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_max_peak_change_per_day_min),
    OApsAIMIPkpdStateDiaH("aimi_pkpd_state_dia_h", 6.0, 4.0, 24.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_state_dia_h),
    OApsAIMIPkpdStatePeakMin("aimi_pkpd_state_peak_min", 75.0, 40.0, 300.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_state_peak_min),
    OApsAIMIPkpdStatePriorPeak("aimi_pkpd_state_prior_peak", 75.0, 0.0, 300.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_state_prior_peak),
    OApsAIMIPkpdStatePhysioPeak("aimi_pkpd_state_physio_peak", 0.0, -100.0, 100.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_state_physio_peak),
    OApsAIMIPkpdStateSitePeak("aimi_pkpd_state_site_peak", 0.0, -100.0, 100.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_state_site_peak),
    OApsAIMIPkpdStateTrajectoryPeak("aimi_pkpd_state_traj_peak", 0.0, -100.0, 100.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_state_trajectory_peak),
    OApsAIMIPkpdStateEffectivePeak("aimi_pkpd_state_effective_peak", 75.0, 35.0, 300.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_state_effective_peak),
    OApsAIMIPeakGovernorLearnedWeight(
        key = "aimi_peak_governor_learned_weight",
        defaultValue = 0.55,
        min = 0.0,
        max = 1.0,
        title = KeysStrings.pref_title_aimi_peak_governor_learned_weight,
        summary = KeysStrings.pref_summary_aimi_peak_governor_learned_weight,
    ),
    OApsAIMIDiaGovernorLearnedWeight(
        key = "aimi_dia_governor_learned_weight",
        defaultValue = 0.45,
        min = 0.0,
        max = 1.0,
        title = KeysStrings.pref_title_oaps_aimi_dia_governor_learned_weight,
    ),
    OApsAIMIIsfFusionMinFactor("aimi_isf_fusion_min_factor", 0.75, 0.3, 1.0, title = KeysStrings.pref_title_oaps_aimi_isf_fusion_min_factor),
    OApsAIMIIsfFusionMaxFactor("aimi_isf_fusion_max_factor", 2.0, 1.0, 2.0, title = KeysStrings.pref_title_oaps_aimi_isf_fusion_max_factor),
    OApsAIMIIsfFusionMaxChangePerTick("aimi_isf_fusion_max_change_per_tick", 0.4, 0.0, 0.5, title = KeysStrings.pref_title_oaps_aimi_isf_fusion_max_change_per_tick),
    /** Max relative DynISF change from trajectory tuning when a tick qualifies (rise or fall). */
    OApsAIMIDynIsfTrajectoryMaxFraction(
        key = "aimi_dyn_isf_trajectory_max_fraction",
        defaultValue = 0.06,
        min = 0.02,
        max = 0.12,
        title = KeysStrings.pref_title_aimi_dyn_isf_trajectory_max_fraction,
        summary = KeysStrings.pref_summary_aimi_dyn_isf_trajectory_max_fraction,
        dependency = BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled,
    ),
    OApsAIMISmbTailThreshold("aimi_smb_tail_threshold", 0.25, 0.0, 1.0, title = KeysStrings.pref_title_oaps_aimi_smb_tail_threshold),
    OApsAIMISmbTailDamping("aimi_smb_tail_damping", 0.85, 0.0, 1.0, title = KeysStrings.pref_title_oaps_aimi_smb_tail_damping),
    OApsAIMISmbExerciseDamping("aimi_smb_exercise_damping", 0.6, 0.0, 1.0, title = KeysStrings.pref_title_oaps_aimi_smb_exercise_damping),
    OApsAIMISmbLateFatDamping("aimi_smb_late_fat_damping", 0.7, 0.0, 1.0, title = KeysStrings.pref_title_oaps_aimi_smb_late_fat_damping),
    OApsAIMIPkpdPragmaticReliefMinFactor("aimi_pkpd_pragmatic_relief_min_factor", 0.75, 0.50, 1.0, title = KeysStrings.pref_title_oaps_aimi_pkpd_pragmatic_relief_min_factor),
    OApsAIMIRedCarpetRestoreThreshold("aimi_red_carpet_restore_threshold", 0.75, 0.50, 0.95, title = KeysStrings.pref_title_oaps_aimi_red_carpet_restore_threshold),
    OApsAIMIPriorityMaxIobFactor("aimi_priority_max_iob_factor", 1.20, 1.0, 1.6, title = KeysStrings.pref_title_oaps_aimi_priority_max_iob_factor),
    OApsAIMIPriorityMaxIobExtraU("aimi_priority_max_iob_extra_u", 2.0, 0.0, 5.0, title = KeysStrings.pref_title_oaps_aimi_priority_max_iob_extra_u),
    // ❌ TIME-BASED REACTIVITY REMOVED - replaced by UnifiedReactivityLearner.globalFactor
    // Previously: OApsAIMIMorningFactor, OApsAIMIAfternoonFactor, OApsAIMIEveningFactor
    OApsAIMIMealFactor("key_oaps_aimi_meal_factor", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_meal_factor),
    OApsAIMIFCLFactor("key_oaps_aimi_FCL_factor", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_fcl_factor),
    OApsAIMIBFFactor("key_oaps_aimi_BF_factor", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_bf_factor),
    OApsAIMIBFPrebolus("key_prebolus_BF_mode", 2.5, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_bf_prebolus),
    OApsAIMIBFPrebolus2("key_prebolus2_BF_mode", 2.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_bf_prebolus2),
    OApsAIMILunchFactor("key_oaps_aimi_lunch_factor", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_lunch_factor),
    OApsAIMIDinnerFactor("key_oaps_aimi_dinner_factor", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_dinner_factor),
    OApsAIMIHCFactor("key_oaps_aimi_HC_factor", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_hc_factor),
    OApsAIMISnackFactor("key_oaps_aimi_snack_factor", 50.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_snack_factor),
    // ❌ HYPER REACTIVITY REMOVED - replaced by UnifiedReactivityLearner.globalFactor
    // Previously: OApsAIMIHyperFactor
    OApsAIMIsleepFactor("key_oaps_aimi_sleep_factor", 60.0, 1.0, 150.0, title = KeysStrings.pref_title_oaps_aimi_sleep_factor),
    OApsAIMIMealPrebolus("key_prebolus_meal_mode", 2.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_meal_prebolus),
    OApsAIMIautodrivePrebolus("key_prebolus_autodrive_mode", 1.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_autodrive_prebolus),
    OApsAIMIautodrivesmallPrebolus("key_prebolussmall_autodrive_mode", 0.1, 0.05, 2.0, title = KeysStrings.pref_title_oaps_aimi_autodrivesmall_prebolus),
    OApsAIMIcombinedDelta("key_combinedDelta_autodrive_mode", 1.0, 0.1, 20.0, title = KeysStrings.pref_title_oaps_aimi_combined_delta),
    OApsAIMIAutodriveDeviation("key_mindeviation_autodrive_mode", 1.0, 0.1, 5.0, title = KeysStrings.pref_title_oaps_aimi_autodrive_deviation),
    OApsAIMIAutodriveAcceleration("key_Acceleration_autodrive_mode", 1.0, 0.1, 5.0, title = KeysStrings.pref_title_oaps_aimi_autodrive_acceleration),
    OApsAIMILunchPrebolus("key_prebolus_lunch_mode", 2.5, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_lunch_prebolus),
    OApsAIMILunchPrebolus2("key_prebolus2_lunch_mode", 2.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_lunch_prebolus2),
    OApsAIMIDinnerPrebolus("key_prebolus_dinner_mode", 2.5, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_dinner_prebolus),
    OApsAIMIDinnerPrebolus2("key_prebolus2_dinner_mode", 2.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_dinner_prebolus2),
    OApsAIMISnackPrebolus("key_prebolus_snack_mode", 1.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_snack_prebolus),
    OApsAIMIHighCarbPrebolus("key_prebolus_highcarb_mode", 5.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_high_carb_prebolus),
    OApsAIMIHighCarbPrebolus2("key_prebolus_highcarb_mode2", 5.0, 0.1, 10.0, title = KeysStrings.pref_title_oaps_aimi_high_carb_prebolus2),
    OApsAIMIwcycledateday("key_wcycledateday", 1.0, 1.0, 31.0, title = KeysStrings.pref_title_oaps_aimi_wcycledateday),
    OApsAIMIWCycleClampMin("key_wcycle_clamp_min", 0.8, 0.5, 1.0, title = KeysStrings.pref_title_oaps_aimi_w_cycle_clamp_min),
    OApsAIMIWCycleClampMax("key_wcycle_clamp_max", 1.25, 1.0, 2.0, title = KeysStrings.pref_title_oaps_aimi_w_cycle_clamp_max),
    OApsAIMINightGrowthMinRiseSlope("key_oaps_aimi_ngr_min_rise_slope", 5.0, 0.5, 30.0, title = KeysStrings.pref_title_oaps_aimi_night_growth_min_rise_slope),
    OApsAIMINightGrowthSmbMultiplier("key_oaps_aimi_ngr_smb_multiplier", 1.2, 1.0, 1.5, title = KeysStrings.pref_title_oaps_aimi_night_growth_smb_multiplier),
    OApsAIMINightGrowthBasalMultiplier("key_oaps_aimi_ngr_basal_multiplier", 1.1, 1.0, 1.5, title = KeysStrings.pref_title_oaps_aimi_night_growth_basal_multiplier),
    OApsAIMINightGrowthMaxSmbClamp("key_oaps_aimi_ngr_max_smb_clamp", 1.2, 0.1, 5.0, title = KeysStrings.pref_title_oaps_aimi_night_growth_max_smb_clamp),
    OApsAIMINightGrowthMaxIobExtra("key_oaps_aimi_ngr_max_iob_extra", 0.5, 0.0, 3.0, title = KeysStrings.pref_title_oaps_aimi_night_growth_max_iob_extra),
    OApsAIMIActivityBasalCapFactor("key_aimi_activity_basal_cap_factor", 1.3, 0.5, 3.0, title = KeysStrings.pref_title_oaps_aimi_activity_basal_cap_factor),
    // --- AIMI Adaptive Basal ---
    OApsAIMIHighBg(key = "OApsAIMIHighBg", defaultValue = 180.0, min = 140.0, max = 250.0, title = KeysStrings.pref_title_oaps_aimi_high_bg), // high BG that starts plateau corrections
    OApsAIMIHyperEstablishedDevMgdl(
        key = "key_aimi_hyper_established_dev_mgdl",
        defaultValue = 0.0,
        min = 0.0,
        max = 160.0,
        title = KeysStrings.pref_title_aimi_hyper_established_dev,
        summary = KeysStrings.pref_summary_aimi_hyper_established_dev,
        dependency = BooleanKey.OApsAIMIHyperTrajectoryRelease,
    ),
    OApsAIMIHyperDeepDevMgdl(
        key = "key_aimi_hyper_deep_dev_mgdl",
        defaultValue = 0.0,
        min = 0.0,
        max = 200.0,
        title = KeysStrings.pref_title_aimi_hyper_deep_dev,
        summary = KeysStrings.pref_summary_aimi_hyper_deep_dev,
        dependency = BooleanKey.OApsAIMIHyperTrajectoryRelease,
    ),
    OApsAIMIPlateauBandAbs(key = "OApsAIMIPlateauBandAbs", defaultValue = 2.5, min = 0.5, max = 6.0, title = KeysStrings.pref_title_oaps_aimi_plateau_band_abs), // plateau band (|delta| <= X mg/dL per 5 min)
    OApsAIMIR2Confident(key = "OApsAIMIR2Confident", defaultValue = 0.7, min = 0.3, max = 0.95, title = KeysStrings.pref_title_oaps_aimi_r2_confident), // quadratic fit confidence
    OApsAIMIMaxMultiplier(key = "OApsAIMIMaxMultiplier", defaultValue = 1.6, min = 1.0, max = 2.5, title = KeysStrings.pref_title_oaps_aimi_max_multiplier), // max basal multiplier vs profile
    OApsAIMIKickerStep(key = "OApsAIMIKickerStep", defaultValue = 0.15, min = 0.05, max = 0.5, title = KeysStrings.pref_title_oaps_aimi_kicker_step), // plateau kicker step (multiply increment)
    OApsAIMIKickerMinUph(key = "OApsAIMIKickerMinUph", defaultValue = 0.2, min = 0.05, max = 1.0, title = KeysStrings.pref_title_oaps_aimi_kicker_min_uph), // plancher absolu U/h pour les kicks très bas
    OApsAIMIZeroResumeFrac(key = "OApsAIMIZeroResumeFrac", defaultValue = 0.25, min = 0.05, max = 0.8, title = KeysStrings.pref_title_oaps_aimi_zero_resume_frac), // profile basal fraction for micro resume
    OApsAIMIAntiStallBias(key = "OApsAIMIAntiStallBias", defaultValue = 0.10, min = 0.0, max = 0.5, title = KeysStrings.pref_title_oaps_aimi_anti_stall_bias), // anti-stall lift bias (+%)
    OApsAIMIDeltaPosRelease(key = "OApsAIMIDeltaPosRelease", defaultValue = 1.0, min = 0.5, max = 3.0, title = KeysStrings.pref_title_oaps_aimi_delta_pos_release), // positive delta that stops intensification
    AimiUamConfidence(key = "AIMI_UAM_CONFIDENCE", defaultValue = 0.5, min = 0.0, max = 1.0, title = KeysStrings.pref_title_aimi_uam_confidence),
    OApsAIMILastEstimatedCarbs(key = "OApsAIMILastEstimatedCarbs", defaultValue = 0.0, min = 0.0, max = 300.0, title = KeysStrings.pref_title_oaps_aimi_last_estimated_carbs), // Meal Advisor Estimate
    OApsAIMILastEstimatedCarbTime(key = "OApsAIMILastEstimatedCarbTime", defaultValue = 0.0, min = 0.0, max = 20000000000000.0, title = KeysStrings.pref_title_oaps_aimi_last_estimated_carb_time), // Timestamp as Double
    // 🌸 Endometriosis & Cycle Management (MTR)
    AimiEndometriosisBasalMult("aimi_endo_basal_mult", 1.3, 1.0, 2.0, title = KeysStrings.pref_title_aimi_endometriosis_basal_mult),
    AimiEndometriosisSmbDampen("aimi_endo_smb_dampen", 0.7, 0.0, 1.0, title = KeysStrings.pref_title_aimi_endometriosis_smb_dampen),
    // 🌀 Adaptive Kernel Bank (Cosine Gate)
    AimiCosineGateAlpha("aimi_cosine_gate_alpha", 2.0, 0.1, 10.0, title = KeysStrings.pref_title_aimi_cosine_gate_alpha),
    AimiCosineGateMinDataQuality("aimi_cosine_gate_min_dq", 0.3, 0.0, 1.0, title = KeysStrings.pref_title_aimi_cosine_gate_min_data_quality),
    AimiCosineGateMinSensitivity("aimi_cosine_gate_min_sens", 0.7, 0.5, 1.0, title = KeysStrings.pref_title_aimi_cosine_gate_min_sensitivity),
    AimiCosineGateMaxSensitivity("aimi_cosine_gate_max_sens", 1.3, 1.0, 2.0, title = KeysStrings.pref_title_aimi_cosine_gate_max_sensitivity),
    // --- Straight-line tube advisor (MPC-lite on SMB + optional basal trim) ---
    AimiTubeHypoFloorMgdl(
        key = "key_aimi_tube_hypo_floor_mgdl",
        defaultValue = 72.0,
        min = 65.0,
        max = 90.0,
        title = KeysStrings.aimi_tube_hypo_floor_title,
        summary = KeysStrings.aimi_tube_hypo_floor_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.MGDL,
    ),
    AimiTubeHyperBandMgdl(
        key = "key_aimi_tube_hyper_band_mgdl",
        defaultValue = 35.0,
        min = 15.0,
        max = 55.0,
        title = KeysStrings.aimi_tube_hyper_band_title,
        summary = KeysStrings.aimi_tube_hyper_band_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.MGDL,
    ),
    AimiTubeAggressiveness(
        key = "key_aimi_tube_aggressiveness",
        defaultValue = 1.0,
        min = 0.5,
        max = 2.0,
        title = KeysStrings.aimi_tube_aggressiveness_title,
        summary = KeysStrings.aimi_tube_aggressiveness_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.DOUBLE_2,
    ),
    AimiTubeBasalTrimMax(
        key = "key_aimi_tube_basal_trim_max",
        defaultValue = 0.12,
        min = 0.0,
        max = 0.25,
        title = KeysStrings.aimi_tube_basal_trim_title,
        summary = KeysStrings.aimi_tube_basal_trim_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.DOUBLE_2,
    ),
    AimiTubeKappaSafetyMargin(
        key = "key_aimi_tube_kappa_margin",
        defaultValue = 0.08,
        min = 0.0,
        max = 0.35,
        title = KeysStrings.aimi_tube_kappa_margin_title,
        summary = KeysStrings.aimi_tube_kappa_margin_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.DOUBLE_2,
    ),
    // --- T3C Enhancements ---
    OApsAIMIT3cActivationThreshold("key_aimi_t3c_activation_threshold", 100.0, 100.0, 250.0, title = KeysStrings.pref_title_oaps_aimi_t3c_activation_threshold),
    /** 0 = parabolic PI only (legacy). >0 blends eventual BG + prediction curve timing into T3C basal. */
    OApsAIMIT3cAnticipationStrength("key_aimi_t3c_anticipation_strength", 0.0, 0.0, 1.0, title = KeysStrings.pref_title_oaps_aimi_t3c_anticipation_strength),
    OApsAIMIT3cAggressiveness("key_aimi_t3c_aggressiveness", 1.0, 0.5, 3.0, title = KeysStrings.pref_title_oaps_aimi_t3c_aggressiveness),
    /** CFRD mode: minimum LGS threshold (mg/dL) for T3C anticipation.
     *  Impaired glucagon counter-regulation in CFRD requires a higher safety floor than standard DT1. */
    OApsAIMIT3cCfrdLgsFloorMgdl(
        key = "key_aimi_t3c_cfrd_lgs_floor",
        defaultValue = 95.0,
        min = 70.0,
        max = 95.0,
        title = KeysStrings.pref_title_aimi_t3c_cfrd_lgs_floor,
        summary = KeysStrings.pref_summary_aimi_t3c_cfrd_lgs_floor,
        dependency = BooleanKey.OApsAIMIT3cCfrdMode,
        unitType = UnitType.NONE,
    ),
    /** CFRD mode: exocrine malabsorption COB delay (minutes).
     *  Shifts the COB absorption curve forward to account for delayed / irregular carbohydrate digestion. */
    OApsAIMIT3cCfrdCobDelayMin(
        key = "key_aimi_t3c_cfrd_cob_delay_min",
        defaultValue = 30.0,
        min = 0.0,
        max = 90.0,
        title = KeysStrings.pref_title_aimi_t3c_cfrd_cob_delay,
        summary = KeysStrings.pref_summary_aimi_t3c_cfrd_cob_delay,
        dependency = BooleanKey.OApsAIMIT3cCfrdMode,
        unitType = UnitType.NONE,
    ),
    /** Undeclared-meal COB estimation: hard upper bound (grams) the estimator may inject into the
     *  prediction path. Conservative by default. Only active when [BooleanKey.OApsAIMIUndeclaredCobEnabled] is on. */
    OApsAIMIUndeclaredCobMaxG(
        "key_aimi_undeclared_cob_max_g", 25.0, 5.0, 80.0,
        title = KeysStrings.pref_title_aimi_undeclared_cob_max_g,
        summary = KeysStrings.pref_summary_aimi_undeclared_cob_max_g,
        dependency = BooleanKey.OApsAIMIUndeclaredCobEnabled,
    ),
    /**
     * Upper bound of the Universal Adaptive Basal multiplier.
     *
     * The old default of 1.0 capped the learned head at neutral, so it could only ever CUT basal and
     * never raise it. With that value the adaptive basal cannot learn a boost by construction, whatever
     * the data says. 1.3 leaves a small, symmetric-looking room above neutral.
     */
    OApsAIMIAdaptiveBasalMaxScaling("key_aimi_adaptive_basal_max_scaling", 1.3, 0.5, 2.0, title = KeysStrings.pref_title_oaps_aimi_adaptive_basal_max_scaling),
    // --- AIMI adaptive basal governance (on-device; depends on Universal Adaptive Basal) ---
    OApsAIMIGovernanceHypoRateEnter(
        key = "key_aimi_gov_hypo_rate_enter",
        defaultValue = 0.20,
        min = 0.05,
        max = 0.45,
        title = KeysStrings.aimi_gov_hypo_rate_enter_title,
        summary = KeysStrings.aimi_gov_hypo_rate_enter_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHypoRateExit(
        key = "key_aimi_gov_hypo_rate_exit",
        defaultValue = 0.12,
        min = 0.02,
        max = 0.44,
        title = KeysStrings.aimi_gov_hypo_rate_exit_title,
        summary = KeysStrings.aimi_gov_hypo_rate_exit_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHypoBgMgdl(
        key = "key_aimi_gov_hypo_bg_mgdl",
        defaultValue = 80.0,
        min = 65.0,
        max = 100.0,
        title = KeysStrings.aimi_gov_hypo_bg_mgdl_title,
        summary = KeysStrings.aimi_gov_hypo_bg_mgdl_summary,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
        unitType = UnitType.MGDL,
    ),
    OApsAIMIGovernanceSevereHypoBgMgdl(
        key = "key_aimi_gov_severe_hypo_bg_mgdl",
        defaultValue = 70.0,
        min = 54.0,
        max = 85.0,
        title = KeysStrings.aimi_gov_severe_hypo_bg_mgdl_title,
        summary = KeysStrings.aimi_gov_severe_hypo_bg_mgdl_summary,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
        unitType = UnitType.MGDL,
    ),
    /**
     * Basal scaling floor while governance holds back.
     *
     * The minimum is 0.80 because 0.80 is the runtime clamp of the LEARNED basal channel (N),
     * `BasalNeuralLearner.RUNTIME_BASAL_FLOOR`. A hold floor under 0.80 was a silent no-op on that
     * channel, and it made a held-back basal look exactly like a dead learned model.
     *
     * This does NOT mean the applied basal multiplier can never be under 0.80. The heuristic channel (H)
     * still floors at 0.70 (the `hMult` clamp in `DetermineBasalaimiSMB2`, and `BasalLearner.CLAMP_MIN`),
     * and the blend keeps the SMALLER of the two channels as soon as either one is defensive, so 0.70 can
     * still reach the pump. Whether H should floor at 0.80 too is a therapy decision that needs field
     * evidence, so it is not changed here.
     *
     * To tell those cases apart, read `n_raw` and `n_source` in the `adaptive_basal` block of
     * AIMI_Decisions.jsonl. A multiplier of exactly 0.70 on 100% of ticks was the field symptom on two
     * patient devices 40 days apart, and it stayed ambiguous because several mechanisms print that same
     * number and nothing in the exported data separated them.
     */
    OApsAIMIGovernanceHoldBasalFloorRate(
        key = "key_aimi_gov_hold_basal_floor_rate",
        defaultValue = 0.85,
        min = 0.80,
        max = 0.95,
        title = KeysStrings.aimi_gov_hold_basal_floor_rate_title,
        summary = KeysStrings.aimi_gov_hold_basal_floor_rate_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldBasalDecayRate(
        key = "key_aimi_gov_hold_basal_decay_rate",
        defaultValue = 0.98,
        min = 0.90,
        max = 0.999,
        title = KeysStrings.aimi_gov_hold_basal_decay_rate_title,
        summary = KeysStrings.aimi_gov_hold_basal_decay_rate_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggFloorRate(
        key = "key_aimi_gov_hold_agg_floor_rate",
        defaultValue = 0.70,
        min = 0.50,
        max = 0.90,
        title = KeysStrings.aimi_gov_hold_agg_floor_rate_title,
        summary = KeysStrings.aimi_gov_hold_agg_floor_rate_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggDecayRate(
        key = "key_aimi_gov_hold_agg_decay_rate",
        defaultValue = 0.97,
        min = 0.90,
        max = 0.999,
        title = KeysStrings.aimi_gov_hold_agg_decay_rate_title,
        summary = KeysStrings.aimi_gov_hold_agg_decay_rate_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    /** Same 0.80 runtime-clamp reason as [OApsAIMIGovernanceHoldBasalFloorRate]. */
    OApsAIMIGovernanceHoldBasalFloorSevere(
        key = "key_aimi_gov_hold_basal_floor_severe",
        defaultValue = 0.88,
        min = 0.80,
        max = 0.95,
        title = KeysStrings.aimi_gov_hold_basal_floor_severe_title,
        summary = KeysStrings.aimi_gov_hold_basal_floor_severe_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldBasalDecaySevere(
        key = "key_aimi_gov_hold_basal_decay_severe",
        defaultValue = 0.975,
        min = 0.90,
        max = 0.999,
        title = KeysStrings.aimi_gov_hold_basal_decay_severe_title,
        summary = KeysStrings.aimi_gov_hold_basal_decay_severe_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggFloorSevere(
        key = "key_aimi_gov_hold_agg_floor_severe",
        defaultValue = 0.72,
        min = 0.50,
        max = 0.90,
        title = KeysStrings.aimi_gov_hold_agg_floor_severe_title,
        summary = KeysStrings.aimi_gov_hold_agg_floor_severe_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggDecaySevere(
        key = "key_aimi_gov_hold_agg_decay_severe",
        defaultValue = 0.965,
        min = 0.90,
        max = 0.999,
        title = KeysStrings.aimi_gov_hold_agg_decay_severe_title,
        summary = KeysStrings.aimi_gov_hold_agg_decay_severe_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    /** Recent governance samples (5-min cadence) used for short-horizon prediction relief (A3). */
    OApsAIMIGovernanceAnticipationLookbackSamples(
        key = "key_aimi_gov_anticipation_lookback_samples",
        defaultValue = 18.0,
        min = 6.0,
        max = 96.0,
        title = KeysStrings.aimi_gov_anticipation_lookback_samples_title,
        summary = KeysStrings.aimi_gov_anticipation_lookback_samples_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    /** Min predicted BG must be at least hypo threshold + this margin (mg/dL) to count toward anticipation relief. */
    OApsAIMIGovernanceAnticipationMarginMgdl(
        key = "key_aimi_gov_anticipation_margin_mgdl",
        defaultValue = 12.0,
        min = 0.0,
        max = 40.0,
        title = KeysStrings.aimi_gov_anticipation_margin_mgdl_title,
        summary = KeysStrings.aimi_gov_anticipation_margin_mgdl_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.MGDL,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    /** Max fraction of weighted hypo rate removed when anticipation relief is full (0–1). */
    OApsAIMIGovernanceAnticipationHypoDamp(
        key = "key_aimi_gov_anticipation_hypo_damp",
        defaultValue = 0.55,
        min = 0.0,
        max = 0.95,
        title = KeysStrings.aimi_gov_anticipation_hypo_damp_title,
        summary = KeysStrings.aimi_gov_anticipation_hypo_damp_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    /** Max softening of HOLD basal/agg decay toward neutral when anticipation relief is full (0–1). */
    OApsAIMIGovernanceAnticipationDecayBlendMax(
        key = "key_aimi_gov_anticipation_decay_blend_max",
        defaultValue = 0.50,
        min = 0.0,
        max = 1.0,
        title = KeysStrings.aimi_gov_anticipation_decay_blend_max_title,
        summary = KeysStrings.aimi_gov_anticipation_decay_blend_max_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    autodriveMaxBasal("autodrive_max_basal", 1.0, 0.05, 25.0, title = KeysStrings.pref_title_autodrive_max_basal),
    meal_modes_MaxBasal("meal_modes_max_basal", 1.0, 0.05, 25.0, title = KeysStrings.pref_title_meal_modes_max_basal),

    ;

}
