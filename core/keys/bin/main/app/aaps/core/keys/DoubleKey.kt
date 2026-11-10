package app.aaps.core.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey

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
    override val unitType: UnitType = UnitType.NONE
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
        unitType = UnitType.INSULIN
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
        unitType = UnitType.INSULIN
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
        unitType = UnitType.INSULIN
    ),
    ActionsFillButton1(key = "fill_button1", defaultValue = 0.3, min = 0.05, max = 20.0, titleResId = R.string.pref_title_fill_button_1, defaultedBySM = true, hideParentScreenIfHidden = true, unitType = UnitType.INSULIN),
    ActionsFillButton2(key = "fill_button2", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_2, defaultedBySM = true, unitType = UnitType.INSULIN),
    ActionsFillButton3(key = "fill_button3", defaultValue = 0.0, min = 0.0, max = 20.0, titleResId = R.string.pref_title_fill_button_3, defaultedBySM = true, unitType = UnitType.INSULIN),
    SafetyMaxBolus(key = "treatmentssafety_maxbolus", defaultValue = 3.0, min = 0.1, max = 60.0, titleResId = R.string.pref_title_max_bolus, unitType = UnitType.INSULIN),
    ApsMaxBasal(
        key = "openapsma_max_basal",
        defaultValue = 1.0,
        min = 0.1,
        max = 25.0,
        titleResId = R.string.pref_title_max_basal,
        summaryResId = R.string.openapsma_max_basal_summary,
        defaultedBySM = true,
        calculatedBySM = true,
        unitType = UnitType.INSULIN_RATE
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
        unitType = UnitType.INSULIN
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
        unitType = UnitType.INSULIN
    ),
    ApsMaxDailyMultiplier(
        key = "openapsama_max_daily_safety_multiplier",
        defaultValue = 3.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_max_daily_multiplier,
        summaryResId = R.string.openapsama_max_daily_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsMaxCurrentBasalMultiplier(
        key = "openapsama_current_basal_safety_multiplier",
        defaultValue = 4.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_current_basal_multiplier,
        summaryResId = R.string.openapsama_current_basal_safety_multiplier_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAmaBolusSnoozeDivisor(
        key = "bolussnooze_dia_divisor",
        defaultValue = 2.0,
        min = 1.0,
        max = 10.0,
        titleResId = R.string.pref_title_bolus_snooze_divisor,
        summaryResId = R.string.openapsama_bolus_snooze_dia_divisor_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAmaMin5MinCarbsImpact(
        key = "openapsama_min_5m_carbimpact",
        defaultValue = 3.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_ama_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsSmbMin5MinCarbsImpact(
        key = "openaps_smb_min_5m_carbimpact",
        defaultValue = 8.0,
        min = 1.0,
        max = 12.0,
        titleResId = R.string.pref_title_smb_min_5m_carbs_impact,
        summaryResId = R.string.openapsama_min_5m_carb_impact_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    AbsorptionCutOff(key = "absorption_cutoff", defaultValue = 6.0, min = 4.0, max = 10.0, titleResId = R.string.pref_title_absorption_cutoff, summaryResId = R.string.absorption_cutoff_summary, unitType = UnitType.HOURS_DOUBLE),
    AbsorptionMaxTime(key = "absorption_maxtime", defaultValue = 6.0, min = 4.0, max = 10.0, titleResId = R.string.pref_title_absorption_maxtime, summaryResId = R.string.absorption_max_time_summary, unitType = UnitType.HOURS_DOUBLE),
    AutosensMin(
        key = "autosens_min",
        defaultValue = 0.7,
        min = 0.1,
        max = 1.0,
        titleResId = R.string.pref_title_autosens_min,
        summaryResId = R.string.openapsama_autosens_min_summary,
        defaultedBySM = true,
        hideParentScreenIfHidden = true,
        unitType = UnitType.DOUBLE
    ),
    AutosensMax(key = "autosens_max", defaultValue = 1.2, min = 0.5, max = 3.0, titleResId = R.string.pref_title_autosens_max, summaryResId = R.string.openapsama_autosens_max_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfMin(key = "autoISF_min", defaultValue = 1.0, min = 0.3, max = 1.0, titleResId = R.string.pref_title_autoisf_min, summaryResId = R.string.openapsama_autoISF_min_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfMax(key = "autoISF_max", defaultValue = 1.0, min = 1.0, max = 3.0, titleResId = R.string.pref_title_autoisf_max, summaryResId = R.string.openapsama_autoISF_max_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfBgAccelWeight(
        key = "bgAccel_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_accel_weight,
        summaryResId = R.string.openapsama_bgAccel_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfBgBrakeWeight(
        key = "bgBrake_ISF_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 1.0,
        titleResId = R.string.pref_title_bg_brake_weight,
        summaryResId = R.string.openapsama_bgBrake_ISF_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfLowBgWeight(
        key = "lower_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_low_bg_weight,
        summaryResId = R.string.openapsama_lower_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfHighBgWeight(
        key = "higher_ISFrange_weight",
        defaultValue = 0.0,
        min = 0.0,
        max = 2.0,
        titleResId = R.string.pref_title_high_bg_weight,
        summaryResId = R.string.openapsama_higher_ISFrange_weight_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfSmbDeliveryRatioBgRange(
        key = "openapsama_smb_delivery_ratio_bg_range",
        defaultValue = 0.0,
        min = 0.0,
        max = 100.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_bg_range,
        summaryResId = R.string.openapsama_smb_delivery_ratio_bg_range_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfPpWeight(key = "pp_ISF_weight", defaultValue = 0.0, min = 0.0, max = 1.0, titleResId = R.string.pref_title_pp_weight, summaryResId = R.string.openapsama_pp_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfDuraWeight(key = "dura_ISF_weight", defaultValue = 0.0, min = 0.0, max = 3.0, titleResId = R.string.pref_title_dura_weight, summaryResId = R.string.openapsama_dura_ISF_weight_summary, defaultedBySM = true, unitType = UnitType.DOUBLE),
    ApsAutoIsfSmbDeliveryRatio(
        key = "openapsama_smb_delivery_ratio",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio,
        summaryResId = R.string.openapsama_smb_delivery_ratio_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfSmbDeliveryRatioMin(
        key = "openapsama_smb_delivery_ratio_min",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_min,
        summaryResId = R.string.openapsama_smb_delivery_ratio_min_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfSmbDeliveryRatioMax(
        key = "openapsama_smb_delivery_ratio_max",
        defaultValue = 0.5,
        min = 0.5,
        max = 1.0,
        titleResId = R.string.pref_title_smb_delivery_ratio_max,
        summaryResId = R.string.openapsama_smb_delivery_ratio_max_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
    ),
    ApsAutoIsfSmbMaxRangeExtension(
        key = "openapsama_smb_max_range_extension",
        defaultValue = 1.0,
        min = 1.0,
        max = 5.0,
        titleResId = R.string.pref_title_smb_max_range_extension,
        summaryResId = R.string.openapsama_smb_max_range_extension_summary,
        defaultedBySM = true,
        unitType = UnitType.DOUBLE
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

    OApsAIMIPkpdInitialDiaH("aimi_pkpd_initial_dia_h", 20.0, 6.0, 24.0),
    OApsAIMIPkpdInitialPeakMin("aimi_pkpd_initial_peak_min", 40.0, 35.0, 300.0),
    OApsAIMIPkpdBoundsDiaMinH("aimi_pkpd_bounds_dia_min_h", 4.0, 4.0, 24.0),
    OApsAIMIPkpdBoundsDiaMaxH("aimi_pkpd_bounds_dia_max_h", 24.0, 6.0, 36.0),
    OApsAIMIPkpdBoundsPeakMinMin("aimi_pkpd_bounds_peak_min_min", 30.0, 20.0, 240.0),
    OApsAIMIPkpdBoundsPeakMinMax("aimi_pkpd_bounds_peak_min_max", 240.0, 60.0, 480.0),
    OApsAIMIPkpdMaxDiaChangePerDayH("aimi_pkpd_max_dia_change_per_day_h", 3.0, 0.1, 6.0),
    OApsAIMIPkpdMaxPeakChangePerDayMin("aimi_pkpd_max_peak_change_per_day_min", 20.0, 1.0, 60.0),
    OApsAIMIPkpdStateDiaH("aimi_pkpd_state_dia_h", 20.0, 6.0, 24.0),
    OApsAIMIPkpdStatePeakMin("aimi_pkpd_state_peak_min", 180.0, 40.0, 300.0),
    OApsAIMIIsfFusionMinFactor("aimi_isf_fusion_min_factor", 0.75, 0.3, 1.0),
    OApsAIMIIsfFusionMaxFactor("aimi_isf_fusion_max_factor", 2.0, 1.0, 2.0),
    OApsAIMIIsfFusionMaxChangePerTick("aimi_isf_fusion_max_change_per_tick", 0.4, 0.0, 0.5),
    OApsAIMISmbTailThreshold("aimi_smb_tail_threshold", 0.25, 0.0, 1.0),
    OApsAIMISmbTailDamping("aimi_smb_tail_damping", 0.5, 0.0, 1.0),
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

    // --- AIMI Adaptive Basal ---
    OApsAIMIHighBg(key = "OApsAIMIHighBg", 180.0, 140.0, 250.0), // seuil haut déclenchant les corrections plateau
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

    // --- T3C Enhancements ---
    OApsAIMIT3cActivationThreshold("key_aimi_t3c_activation_threshold", 130.0, 100.0, 250.0),
    /** 0 = parabolic PI only (legacy). >0 blends eventual BG + prediction curve timing into T3C basal. */
    OApsAIMIT3cAnticipationStrength("key_aimi_t3c_anticipation_strength", 0.0, 0.0, 1.0),
    OApsAIMIT3cAggressiveness("key_aimi_t3c_aggressiveness", 1.0, 0.5, 3.0),
    OApsAIMIAdaptiveBasalMaxScaling("key_aimi_adaptive_basal_max_scaling", 1.0, 0.5, 2.0),
}
