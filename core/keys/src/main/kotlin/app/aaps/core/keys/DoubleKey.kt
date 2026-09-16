package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.SyncChannel
import app.aaps.core.keys.interfaces.SyncDirection
import app.aaps.core.keys.interfaces.SyncSpec

enum class DoubleKey(
    override val key: String,
    override val defaultValue: Double,
    override val min: Double,
    override val max: Double,
    override val titleResId: Int = 0,
    override val summaryResId: Int? = null,
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
        titleResId = R.string.pref_title_insulin_button_increment_1,
        summaryResId = R.string.insulin_increment_button_message,
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
        titleResId = R.string.pref_title_insulin_button_increment_2,
        summaryResId = R.string.insulin_increment_button_message,
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
        titleResId = R.string.pref_title_insulin_button_increment_3,
        summaryResId = R.string.insulin_increment_button_message,
        defaultedBySM = true,
        dependency = BooleanKey.OverviewShowInsulinButton,
        unitType = UnitType.INSULIN,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ActionsFillButton1(key = "fill_button1", defaultValue = 0.3, min = 0.05, max = 20.0, titleResId = R.string.pref_title_fill_button_1, defaultedBySM = true, hideParentScreenIfHidden = true, unitType = UnitType.INSULIN),
    ActionsFillButton2(key = "fill_button2", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_2, defaultedBySM = true, unitType = UnitType.INSULIN),
    ActionsFillButton3(key = "fill_button3", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_3, defaultedBySM = true, unitType = UnitType.INSULIN),
    SafetyMaxBolus(key = "treatmentssafety_maxbolus", defaultValue = 3.0, min = 0.1, max = 60.0, titleResId = R.string.pref_title_max_bolus, unitType = UnitType.INSULIN, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsMaxBasal(
        key = "openapsma_max_basal",
        defaultValue = 1.0,
        min = 0.1,
        max = 25.0,
        titleResId = R.string.pref_title_max_basal,
        summaryResId = R.string.openapsma_max_basal_summary,
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
        titleResId = R.string.pref_title_smb_max_iob,
        summaryResId = R.string.openapssmb_max_iob_summary,
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
        titleResId = R.string.pref_title_ama_max_iob,
        summaryResId = R.string.openapsma_max_iob_summary,
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
        titleResId = R.string.pref_title_max_daily_multiplier,
        summaryResId = R.string.openapsama_max_daily_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsMaxCurrentBasalMultiplier(
        key = "openapsama_current_basal_safety_multiplier",
        defaultValue = 4.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_current_basal_multiplier,
        summaryResId = R.string.openapsama_current_basal_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaBolusSnoozeDivisor(
        key = "bolussnooze_dia_divisor",
        defaultValue = 2.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_bolus_snooze_divisor,
        summaryResId = R.string.openapsama_bolus_snooze_dia_divisor_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAmaMin5MinCarbsImpact(
        key = "openapsama_min_5m_carbimpact",
        defaultValue = 3.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_ama_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsSmbMin5MinCarbsImpact(
        key = "openaps_smb_min_5m_carbimpact",
        defaultValue = 8.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_smb_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AbsorptionCutOff(
        key = "absorption_cutoff",
        defaultValue = 6.0,
        min = 4.0,
        max = 10.0,
        titleResId = R.string.pref_title_absorption_cutoff,
        summaryResId = R.string.absorption_cutoff_summary,
        unitType = UnitType.HOURS_DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AbsorptionMaxTime(
        key = "absorption_maxtime",
        defaultValue = 6.0,
        min = 4.0,
        max = 10.0,
        titleResId = R.string.pref_title_absorption_maxtime,
        summaryResId = R.string.absorption_max_time_summary,
        unitType = UnitType.HOURS_DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutosensMin(
        key = "autosens_min",
        defaultValue = 0.7,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_autosens_min,
        summaryResId = R.string.openapsama_autosens_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    AutosensMax(
        key = "autosens_max",
        defaultValue = 1.2,
        min = 0.5,
        max = 3.0,
        titleResId = R.string.pref_title_autosens_max,
        summaryResId = R.string.openapsama_autosens_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfMin(key = "autoISF_min", defaultValue = 1.0, min = 0.3, max = 1.0, titleResId = R.string.pref_title_autoisf_min, summaryResId = R.string.openapsama_autoISF_min_summary, defaultedBySM = true, unitType = UnitType.DOUBLE, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsAutoIsfMax(key = "autoISF_max", defaultValue = 1.0, min = 1.0, max = 3.0, titleResId = R.string.pref_title_autoisf_max, summaryResId = R.string.openapsama_autoISF_max_summary, defaultedBySM = true, unitType = UnitType.DOUBLE, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsAutoIsfBgAccelWeight(
        key = "bgAccel_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_accel_weight,
        summaryResId = R.string.openapsama_bgAccel_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfBgBrakeWeight(
        key = "bgBrake_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_brake_weight,
        summaryResId = R.string.openapsama_bgBrake_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfLowBgWeight(
        key = "lower_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_low_bg_weight,
        summaryResId = R.string.openapsama_lower_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfHighBgWeight(
        key = "higher_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_high_bg_weight,
        summaryResId = R.string.openapsama_higher_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioBgRange(
        key = "openapsama_smb_delivery_ratio_bg_range",
        defaultValue = 0.0,
        min = 0.0,
        max = 100.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_bg_range,
        summaryResId = R.string.openapsama_smb_delivery_ratio_bg_range_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfPpWeight(key = "pp_ISF_weight", defaultValue = 0.0, min = 0.0, max = 0.15, titleResId = R.string.pref_title_pp_weight, summaryResId = R.string.openapsama_pp_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE_3, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsAutoIsfDuraWeight(key = "dura_ISF_weight", defaultValue = 0.0, min = 0.0, max = 3.0, titleResId = R.string.pref_title_dura_weight, summaryResId = R.string.openapsama_dura_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE_2, sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)),
    ApsAutoIsfSmbDeliveryRatio(
        key = "openapsama_smb_delivery_ratio",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio,
        summaryResId = R.string.openapsama_smb_delivery_ratio_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioMin(
        key = "openapsama_smb_delivery_ratio_min",
        defaultValue = 0.5,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_min,
        summaryResId = R.string.openapsama_smb_delivery_ratio_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbDeliveryRatioMax(
        key = "openapsama_smb_delivery_ratio_max",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_max,
        summaryResId = R.string.openapsama_smb_delivery_ratio_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE_2,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),
    ApsAutoIsfSmbMaxRangeExtension(
        key = "openapsama_smb_max_range_extension",
        defaultValue = 1.0,
        min = 1.0,
        max = 5.0,
        titleResId = R.string.pref_title_smb_max_range_extension,
        summaryResId = R.string.openapsama_smb_max_range_extension_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE,
        sync = SyncSpec(SyncChannel.Cold, SyncDirection.Bidirectional)
    ),

    // === Tes ajouts AIMI / custom ===
    EquilMaxBolus("equil_maxbolus", 10.0, 0.1, 25.0),

    OApsAIMIMaxSMB("key_openapsaimi_max_smb", 1.0, 0.05, 15.0),
    OApsAIMIHighBGMaxSMB("key_openapsaimi_high_bg_max_smb", 1.0, 0.05, 15.0),

    OApsAIMIweight("key_aimiweight", 50.0, 1.0, 200.0),
    /** MPC: max insulin (U) per kg body weight per 5-minute dose search; combined with Max SMB / High BG SMB caps. */
    OApsAIMIMpcInsulinUPerKgPerStep("aimi_mpc_insulin_u_per_kg_per_5min", 0.065, 0.03, 0.12),
    OApsAIMICHO("key_cho", 50.0, 1.0, 150.0),
    OApsAIMITDD7("key_tdd7", 40.0, 1.0, 150.0),

    OApsAIMIPkpdInitialDiaH("aimi_pkpd_initial_dia_h", 6.0, 4.0, 24.0),
    OApsAIMIPkpdInitialPeakMin("aimi_pkpd_initial_peak_min", 75.0, 35.0, 300.0),
    OApsAIMIPkpdAnchorDiaH("aimi_pkpd_anchor_dia_h", 4.0, 4.0, 12.0),
    OApsAIMIPkpdAnchorPeakMin("aimi_pkpd_anchor_peak_min", 75.0, 35.0, 180.0),
    OApsAIMIPkpdBoundsDiaMinH("aimi_pkpd_bounds_dia_min_h", 4.0, 4.0, 24.0),
    OApsAIMIPkpdBoundsDiaMaxH("aimi_pkpd_bounds_dia_max_h", 24.0, 6.0, 36.0),
    OApsAIMIPkpdBoundsPeakMinMin("aimi_pkpd_bounds_peak_min_min", 30.0, 20.0, 240.0),
    OApsAIMIPkpdBoundsPeakMinMax("aimi_pkpd_bounds_peak_min_max", 240.0, 60.0, 480.0),
    OApsAIMIPkpdMaxDiaChangePerDayH("aimi_pkpd_max_dia_change_per_day_h", 3.0, 0.1, 6.0),
    OApsAIMIPkpdMaxPeakChangePerDayMin("aimi_pkpd_max_peak_change_per_day_min", 20.0, 1.0, 60.0),
    OApsAIMIPkpdStateDiaH("aimi_pkpd_state_dia_h", 6.0, 4.0, 24.0),
    OApsAIMIPkpdStatePeakMin("aimi_pkpd_state_peak_min", 75.0, 40.0, 300.0),
    OApsAIMIPkpdStatePriorPeak("aimi_pkpd_state_prior_peak", 75.0, 0.0, 300.0),
    OApsAIMIPkpdStatePhysioPeak("aimi_pkpd_state_physio_peak", 0.0, -100.0, 100.0),
    OApsAIMIPkpdStateSitePeak("aimi_pkpd_state_site_peak", 0.0, -100.0, 100.0),
    OApsAIMIPkpdStateTrajectoryPeak("aimi_pkpd_state_traj_peak", 0.0, -100.0, 100.0),
    OApsAIMIPkpdStateEffectivePeak("aimi_pkpd_state_effective_peak", 75.0, 35.0, 300.0),
    OApsAIMIPeakGovernorLearnedWeight(
        key = "aimi_peak_governor_learned_weight",
        defaultValue = 0.55,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_aimi_peak_governor_learned_weight,
        summaryResId = R.string.pref_summary_aimi_peak_governor_learned_weight,
    ),
    OApsAIMIDiaGovernorLearnedWeight(
        key = "aimi_dia_governor_learned_weight",
        defaultValue = 0.45,
        min = 0.0,
        max = 1.0,
    ),
    OApsAIMIIsfFusionMinFactor("aimi_isf_fusion_min_factor", 0.75, 0.3, 1.0),
    OApsAIMIIsfFusionMaxFactor("aimi_isf_fusion_max_factor", 2.0, 1.0, 2.0),
    OApsAIMIIsfFusionMaxChangePerTick("aimi_isf_fusion_max_change_per_tick", 0.4, 0.0, 0.5),
    /** Max relative DynISF change from trajectory tuning when a tick qualifies (rise or fall). */
    OApsAIMIDynIsfTrajectoryMaxFraction(
        key = "aimi_dyn_isf_trajectory_max_fraction",
        defaultValue = 0.06,
        min = 0.02,
        max = 0.12,
        titleResId = R.string.pref_title_aimi_dyn_isf_trajectory_max_fraction,
        summaryResId = R.string.pref_summary_aimi_dyn_isf_trajectory_max_fraction,
        dependency = BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled,
    ),
    OApsAIMISmbTailThreshold("aimi_smb_tail_threshold", 0.25, 0.0, 1.0),
    OApsAIMISmbTailDamping("aimi_smb_tail_damping", 0.85, 0.0, 1.0),
    OApsAIMISmbExerciseDamping("aimi_smb_exercise_damping", 0.6, 0.0, 1.0),
    OApsAIMISmbLateFatDamping("aimi_smb_late_fat_damping", 0.7, 0.0, 1.0),
    OApsAIMIPkpdPragmaticReliefMinFactor("aimi_pkpd_pragmatic_relief_min_factor", 0.75, 0.50, 1.0),
    OApsAIMIRedCarpetRestoreThreshold("aimi_red_carpet_restore_threshold", 0.75, 0.50, 0.95),
    OApsAIMIPriorityMaxIobFactor("aimi_priority_max_iob_factor", 1.20, 1.0, 1.6),
    OApsAIMIPriorityMaxIobExtraU("aimi_priority_max_iob_extra_u", 2.0, 0.0, 5.0),
    // ❌ TIME-BASED REACTIVITY REMOVED - replaced by UnifiedReactivityLearner.globalFactor
    // Previously: OApsAIMIMorningFactor, OApsAIMIAfternoonFactor, OApsAIMIEveningFactor

    OApsAIMIMealFactor("key_oaps_aimi_meal_factor", 50.0, 1.0, 150.0),
    OApsAIMIFCLFactor("key_oaps_aimi_FCL_factor", 50.0, 1.0, 150.0),
    OApsAIMIBFFactor("key_oaps_aimi_BF_factor", 50.0, 1.0, 150.0),

    OApsAIMIBFPrebolus("key_prebolus_BF_mode", 2.5, 0.1, 10.0),
    OApsAIMIBFPrebolus2("key_prebolus2_BF_mode", 2.0, 0.1, 10.0),

    OApsAIMILunchFactor("key_oaps_aimi_lunch_factor", 50.0, 1.0, 150.0),
    OApsAIMIDinnerFactor("key_oaps_aimi_dinner_factor", 50.0, 1.0, 150.0),
    OApsAIMIHCFactor("key_oaps_aimi_HC_factor", 50.0, 1.0, 150.0),
    OApsAIMISnackFactor("key_oaps_aimi_snack_factor", 50.0, 1.0, 150.0),
    // ❌ HYPER REACTIVITY REMOVED - replaced by UnifiedReactivityLearner.globalFactor
    // Previously: OApsAIMIHyperFactor

    OApsAIMIsleepFactor("key_oaps_aimi_sleep_factor", 60.0, 1.0, 150.0),

    /**
     * Insulin spent above profile over the declared-meal window, in units. 0 disarms the gesture even
     * when its key is on. Min is 0 on purpose, unlike the prebolus keys whose min is 0.1.
     */
    OApsAIMIAnticipBudgetU("key_aimi_anticip_budget_u", 2.0, 0.0, 10.0),
    OApsAIMIMealPrebolus("key_prebolus_meal_mode", 2.0, 0.1, 10.0),
    OApsAIMIautodrivePrebolus("key_prebolus_autodrive_mode", 1.0, 0.1, 10.0),
    OApsAIMIautodrivesmallPrebolus("key_prebolussmall_autodrive_mode", 0.1, 0.05, 2.0),

    OApsAIMIcombinedDelta("key_combinedDelta_autodrive_mode", 1.0, 0.1, 20.0),
    OApsAIMIAutodriveDeviation("key_mindeviation_autodrive_mode", 1.0, 0.1, 5.0),
    OApsAIMIAutodriveAcceleration("key_Acceleration_autodrive_mode", 1.0, 0.1, 5.0),

    autodriveMaxBasal("autodrive_max_basal", 1.0, 0.05, 25.0),
    meal_modes_MaxBasal("meal_modes_max_basal", 1.0, 0.05, 25.0),

    OApsAIMILunchPrebolus("key_prebolus_lunch_mode", 2.5, 0.1, 10.0),
    OApsAIMILunchPrebolus2("key_prebolus2_lunch_mode", 2.0, 0.1, 10.0),
    OApsAIMIDinnerPrebolus("key_prebolus_dinner_mode", 2.5, 0.1, 10.0),
    OApsAIMIDinnerPrebolus2("key_prebolus2_dinner_mode", 2.0, 0.1, 10.0),
    OApsAIMISnackPrebolus("key_prebolus_snack_mode", 1.0, 0.1, 10.0),
    OApsAIMIHighCarbPrebolus("key_prebolus_highcarb_mode", 5.0, 0.1, 10.0),
    OApsAIMIHighCarbPrebolus2("key_prebolus_highcarb_mode2", 5.0, 0.1, 10.0),

    OApsAIMIwcycledateday("key_wcycledateday", 1.0, 1.0, 31.0),
    OApsAIMIWCycleClampMin("key_wcycle_clamp_min", 0.8, 0.5, 1.0),
    OApsAIMIWCycleClampMax("key_wcycle_clamp_max", 1.25, 1.0, 2.0),

    OApsAIMINightGrowthMinRiseSlope("key_oaps_aimi_ngr_min_rise_slope", 5.0, 0.5, 30.0),
    OApsAIMINightGrowthSmbMultiplier("key_oaps_aimi_ngr_smb_multiplier", 1.2, 1.0, 1.5),
    OApsAIMINightGrowthBasalMultiplier("key_oaps_aimi_ngr_basal_multiplier", 1.1, 1.0, 1.5),
    OApsAIMINightGrowthMaxSmbClamp("key_oaps_aimi_ngr_max_smb_clamp", 1.2, 0.1, 5.0),
    OApsAIMINightGrowthMaxIobExtra("key_oaps_aimi_ngr_max_iob_extra", 0.5, 0.0, 3.0),
    OApsAIMIActivityBasalCapFactor("key_aimi_activity_basal_cap_factor", 1.3, 0.5, 3.0),

    // --- AIMI Adaptive Basal ---
    OApsAIMIHighBg(key = "OApsAIMIHighBg", 180.0, 140.0, 250.0), // seuil haut déclenchant les corrections plateau
    OApsAIMIHyperEstablishedDevMgdl(
        key = "key_aimi_hyper_established_dev_mgdl",
        defaultValue = 0.0,
        min = 0.0,
        max = 160.0,
        titleResId = R.string.pref_title_aimi_hyper_established_dev,
        summaryResId = R.string.pref_summary_aimi_hyper_established_dev,
        dependency = BooleanKey.OApsAIMIHyperTrajectoryRelease,
    ),
    OApsAIMIHyperDeepDevMgdl(
        key = "key_aimi_hyper_deep_dev_mgdl",
        defaultValue = 0.0,
        min = 0.0,
        max = 200.0,
        titleResId = R.string.pref_title_aimi_hyper_deep_dev,
        summaryResId = R.string.pref_summary_aimi_hyper_deep_dev,
        dependency = BooleanKey.OApsAIMIHyperTrajectoryRelease,
    ),
    OApsAIMIPlateauBandAbs(key = "OApsAIMIPlateauBandAbs", 2.5, 0.5, 6.0), // bande de tolérance du plateau (|Δ| ≤ X mg/dL/5m)
    OApsAIMIR2Confident(key = "OApsAIMIR2Confident", 0.7, 0.3, 0.95), // seuil de confiance du fit quadratique
    OApsAIMIMaxMultiplier(key = "OApsAIMIMaxMultiplier", 1.6, 1.0,2.5), // plafond multiplicatif de la basale (× profil)
    OApsAIMIKickerStep(key = "OApsAIMIKickerStep", 0.15, 0.05, 0.5), // intensité du “kicker” plateau (incrément multiplicatif)
    OApsAIMIKickerMinUph(key = "OApsAIMIKickerMinUph", 0.2,0.05, 1.0), // plancher absolu U/h pour les kicks très bas
    OApsAIMIZeroResumeFrac(key = "OApsAIMIZeroResumeFrac", 0.25, 0.05, 0.8), // fraction du basal profil pour la micro-reprise
    OApsAIMIAntiStallBias(key = "OApsAIMIAntiStallBias", 0.10, 0.0, 0.5), // biais de “décollage” anti-stagnation (+%)
    OApsAIMIDeltaPosRelease(key = "OApsAIMIDeltaPosRelease", 1.0, 0.5, 3.0), // seuil Δ positif au-delà duquel on arrête l’intensification
    AimiUamConfidence (key = "AIMI_UAM_CONFIDENCE", 0.5, 0.0, 1.0),
    OApsAIMILastEstimatedCarbs(key = "OApsAIMILastEstimatedCarbs", 0.0, 0.0, 300.0), // Meal Advisor Estimate

    OApsAIMILastEstimatedCarbTime(key = "OApsAIMILastEstimatedCarbTime", 0.0, 0.0, 20000000000000.0), // Timestamp as Double

    // 🌸 Endometriosis & Cycle Management (MTR)
    AimiEndometriosisBasalMult("aimi_endo_basal_mult", 1.3, 1.0, 2.0),
    AimiEndometriosisSmbDampen("aimi_endo_smb_dampen", 0.7, 0.0, 1.0),

    // 🌀 Adaptive Kernel Bank (Cosine Gate)
    AimiCosineGateAlpha("aimi_cosine_gate_alpha", 2.0, 0.1, 10.0),
    AimiCosineGateMinDataQuality("aimi_cosine_gate_min_dq", 0.3, 0.0, 1.0),
    AimiCosineGateMinSensitivity("aimi_cosine_gate_min_sens", 0.7, 0.5, 1.0),
    AimiCosineGateMaxSensitivity("aimi_cosine_gate_max_sens", 1.3, 1.0, 2.0),

    // --- Straight-line tube advisor (MPC-lite on SMB + optional basal trim) ---
    AimiTubeHypoFloorMgdl(
        key = "key_aimi_tube_hypo_floor_mgdl",
        defaultValue = 72.0,
        min = 65.0,
        max = 90.0,
        titleResId = R.string.aimi_tube_hypo_floor_title,
        summaryResId = R.string.aimi_tube_hypo_floor_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.MGDL,
    ),
    AimiTubeHyperBandMgdl(
        key = "key_aimi_tube_hyper_band_mgdl",
        defaultValue = 35.0,
        min = 15.0,
        max = 55.0,
        titleResId = R.string.aimi_tube_hyper_band_title,
        summaryResId = R.string.aimi_tube_hyper_band_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.MGDL,
    ),
    AimiTubeAggressiveness(
        key = "key_aimi_tube_aggressiveness",
        defaultValue = 1.0,
        min = 0.5,
        max = 2.0,
        titleResId = R.string.aimi_tube_aggressiveness_title,
        summaryResId = R.string.aimi_tube_aggressiveness_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.DOUBLE_2,
    ),
    AimiTubeBasalTrimMax(
        key = "key_aimi_tube_basal_trim_max",
        defaultValue = 0.12,
        min = 0.0,
        max = 0.25,
        titleResId = R.string.aimi_tube_basal_trim_title,
        summaryResId = R.string.aimi_tube_basal_trim_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.DOUBLE_2,
    ),
    AimiTubeKappaSafetyMargin(
        key = "key_aimi_tube_kappa_margin",
        defaultValue = 0.08,
        min = 0.0,
        max = 0.35,
        titleResId = R.string.aimi_tube_kappa_margin_title,
        summaryResId = R.string.aimi_tube_kappa_margin_summary,
        dependency = BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled,
        unitType = UnitType.DOUBLE_2,
    ),

    // --- T3C Enhancements ---
    OApsAIMIT3cActivationThreshold("key_aimi_t3c_activation_threshold", 100.0, 100.0, 250.0),
    /** 0 = parabolic PI only (legacy). >0 blends eventual BG + prediction curve timing into T3C basal. */
    OApsAIMIT3cAnticipationStrength("key_aimi_t3c_anticipation_strength", 0.0, 0.0, 1.0),
    OApsAIMIT3cAggressiveness("key_aimi_t3c_aggressiveness", 1.0, 0.5, 3.0),
    /** CFRD mode: minimum LGS threshold (mg/dL) for T3C anticipation.
     *  Impaired glucagon counter-regulation in CFRD requires a higher safety floor than standard DT1. */
    OApsAIMIT3cCfrdLgsFloorMgdl(
        key = "key_aimi_t3c_cfrd_lgs_floor",
        defaultValue = 95.0,
        min = 70.0,
        max = 95.0,
        titleResId = R.string.pref_title_aimi_t3c_cfrd_lgs_floor,
        summaryResId = R.string.pref_summary_aimi_t3c_cfrd_lgs_floor,
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
        titleResId = R.string.pref_title_aimi_t3c_cfrd_cob_delay,
        summaryResId = R.string.pref_summary_aimi_t3c_cfrd_cob_delay,
        dependency = BooleanKey.OApsAIMIT3cCfrdMode,
        unitType = UnitType.NONE,
    ),
    /** Undeclared-meal COB estimation: hard upper bound (grams) the estimator may inject into the
     *  prediction path. Conservative by default. Only active when [BooleanKey.OApsAIMIUndeclaredCobEnabled] is on. */
    OApsAIMIUndeclaredCobMaxG(
        "key_aimi_undeclared_cob_max_g", 25.0, 5.0, 80.0,
        titleResId = R.string.pref_title_aimi_undeclared_cob_max_g,
        summaryResId = R.string.pref_summary_aimi_undeclared_cob_max_g,
        dependency = BooleanKey.OApsAIMIUndeclaredCobEnabled,
    ),
    /**
     * Upper bound of the Universal Adaptive Basal multiplier.
     *
     * The old default of 1.0 capped the learned head at neutral, so it could only ever CUT basal and
     * never raise it. With that value the adaptive basal cannot learn a boost by construction, whatever
     * the data says. 1.3 leaves a small, symmetric-looking room above neutral.
     */
    OApsAIMIAdaptiveBasalMaxScaling("key_aimi_adaptive_basal_max_scaling", 1.3, 0.5, 2.0),

    // --- AIMI adaptive basal governance (on-device; depends on Universal Adaptive Basal) ---
    OApsAIMIGovernanceHypoRateEnter(
        key = "key_aimi_gov_hypo_rate_enter",
        defaultValue = 0.20,
        min = 0.05,
        max = 0.45,
        titleResId = R.string.aimi_gov_hypo_rate_enter_title,
        summaryResId = R.string.aimi_gov_hypo_rate_enter_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHypoRateExit(
        key = "key_aimi_gov_hypo_rate_exit",
        defaultValue = 0.12,
        min = 0.02,
        max = 0.44,
        titleResId = R.string.aimi_gov_hypo_rate_exit_title,
        summaryResId = R.string.aimi_gov_hypo_rate_exit_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHypoBgMgdl(
        key = "key_aimi_gov_hypo_bg_mgdl",
        defaultValue = 80.0,
        min = 65.0,
        max = 100.0,
        titleResId = R.string.aimi_gov_hypo_bg_mgdl_title,
        summaryResId = R.string.aimi_gov_hypo_bg_mgdl_summary,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
        unitType = UnitType.MGDL,
    ),
    OApsAIMIGovernanceSevereHypoBgMgdl(
        key = "key_aimi_gov_severe_hypo_bg_mgdl",
        defaultValue = 70.0,
        min = 54.0,
        max = 85.0,
        titleResId = R.string.aimi_gov_severe_hypo_bg_mgdl_title,
        summaryResId = R.string.aimi_gov_severe_hypo_bg_mgdl_summary,
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
        titleResId = R.string.aimi_gov_hold_basal_floor_rate_title,
        summaryResId = R.string.aimi_gov_hold_basal_floor_rate_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldBasalDecayRate(
        key = "key_aimi_gov_hold_basal_decay_rate",
        defaultValue = 0.98,
        min = 0.90,
        max = 0.999,
        titleResId = R.string.aimi_gov_hold_basal_decay_rate_title,
        summaryResId = R.string.aimi_gov_hold_basal_decay_rate_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggFloorRate(
        key = "key_aimi_gov_hold_agg_floor_rate",
        defaultValue = 0.70,
        min = 0.50,
        max = 0.90,
        titleResId = R.string.aimi_gov_hold_agg_floor_rate_title,
        summaryResId = R.string.aimi_gov_hold_agg_floor_rate_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggDecayRate(
        key = "key_aimi_gov_hold_agg_decay_rate",
        defaultValue = 0.97,
        min = 0.90,
        max = 0.999,
        titleResId = R.string.aimi_gov_hold_agg_decay_rate_title,
        summaryResId = R.string.aimi_gov_hold_agg_decay_rate_summary,
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
        titleResId = R.string.aimi_gov_hold_basal_floor_severe_title,
        summaryResId = R.string.aimi_gov_hold_basal_floor_severe_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldBasalDecaySevere(
        key = "key_aimi_gov_hold_basal_decay_severe",
        defaultValue = 0.975,
        min = 0.90,
        max = 0.999,
        titleResId = R.string.aimi_gov_hold_basal_decay_severe_title,
        summaryResId = R.string.aimi_gov_hold_basal_decay_severe_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggFloorSevere(
        key = "key_aimi_gov_hold_agg_floor_severe",
        defaultValue = 0.72,
        min = 0.50,
        max = 0.90,
        titleResId = R.string.aimi_gov_hold_agg_floor_severe_title,
        summaryResId = R.string.aimi_gov_hold_agg_floor_severe_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
    OApsAIMIGovernanceHoldAggDecaySevere(
        key = "key_aimi_gov_hold_agg_decay_severe",
        defaultValue = 0.965,
        min = 0.90,
        max = 0.999,
        titleResId = R.string.aimi_gov_hold_agg_decay_severe_title,
        summaryResId = R.string.aimi_gov_hold_agg_decay_severe_summary,
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
        titleResId = R.string.aimi_gov_anticipation_lookback_samples_title,
        summaryResId = R.string.aimi_gov_anticipation_lookback_samples_summary,
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
        titleResId = R.string.aimi_gov_anticipation_margin_mgdl_title,
        summaryResId = R.string.aimi_gov_anticipation_margin_mgdl_summary,
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
        titleResId = R.string.aimi_gov_anticipation_hypo_damp_title,
        summaryResId = R.string.aimi_gov_anticipation_hypo_damp_summary,
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
        titleResId = R.string.aimi_gov_anticipation_decay_blend_max_title,
        summaryResId = R.string.aimi_gov_anticipation_decay_blend_max_summary,
        preferenceType = PreferenceType.TEXT_FIELD,
        unitType = UnitType.DOUBLE_2,
        dependency = BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled,
    ),
}
