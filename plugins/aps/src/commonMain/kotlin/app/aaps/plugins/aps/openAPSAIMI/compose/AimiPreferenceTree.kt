package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.KeysStrings
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.context.ui.AimiPreferenceInfoScreen
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot

/**
 * The AIMI settings tree that `OpenAPSAIMIPlugin.getPreferenceScreenContent` returns.
 *
 * It lives outside the plugin so a test can build it with fakes: the plugin itself needs dozens of
 * services. The order of every list follows the dev_OAPSAIMI Android screens, so a person finds a
 * setting where they knew it. Where dev reads a list from [AimiSettingsScreens.preferenceKeysOf], so
 * does this tree: a section can then never drift from what is drawn.
 *
 * Everything below "User preferences" is reachable by browsing through the link rows added by
 * [aimiWithDrillInLinks]; see that function for why they are needed.
 *
 * @param platformItems entries only the platform can show; a `null` entry is left out of the tree
 * @param controlCenter the AIMI Control Center screen
 * @param settingsAudit the settings audit screen
 */
internal class AimiPreferenceTree(
    private val platformItems: AimiPlatformPreferenceItems,
    private val controlCenter: ComposeScreenContent,
    private val settingsAudit: ComposeScreenContent,
) {

    fun build(icon: ImageVector?): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = ROOT_KEY,
            title = ApsStrings.openapsaimi,
            icon = icon,
            items = rootItems(),
        )

    /**
     * OpenAPS/SMB core keys (same order as `OpenAPSSMBPlugin`), then the nested AIMI sections.
     */
    private fun rootItems(): List<PreferenceItem> = buildList {
        add(DoubleKey.ApsMaxBasal)
        add(DoubleKey.ApsSmbMaxIob)
        add(BooleanKey.ApsUseDynamicSensitivity)
        add(BooleanKey.ApsUseAutosens)
        add(IntKey.ApsDynIsfAdjustmentFactor)
        add(UnitDoubleKey.ApsLgsThreshold)
        add(BooleanKey.ApsDynIsfAdjustSensitivity)
        add(BooleanKey.ApsSensitivityRaisesTarget)
        add(BooleanKey.ApsResistanceLowersTarget)
        add(BooleanKey.ApsUseSmb)
        add(BooleanKey.ApsUseSmbWithHighTt)
        add(BooleanKey.ApsUseSmbAlways)
        add(BooleanKey.ApsUseSmbWithCob)
        add(BooleanKey.ApsUseSmbWithLowTt)
        add(BooleanKey.ApsUseSmbAfterCarbs)
        add(IntKey.ApsMaxSmbFrequency)
        add(IntKey.ApsMaxMinutesOfBasalToLimitSmb)
        add(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb)
        add(BooleanKey.ApsUseUam)
        add(IntKey.ApsCarbsRequestThreshold)
        add(
            PreferenceSubScreenDef(
                key = "openapsaimi_absorption_advanced",
                title = CoreUiStrings.advanced_settings_title,
                items = listOf(
                    ApsIntentKey.LinkToDocs,
                    BooleanKey.ApsAlwaysUseShortDeltas,
                    DoubleKey.ApsMaxDailyMultiplier,
                    DoubleKey.ApsMaxCurrentBasalMultiplier,
                ),
            )
        )
        // Drawn as a header inside the plugin card, so its own sub screens need link rows.
        add(
            PreferenceSubScreenDef(
                key = USER_PREFERENCES_KEY,
                title = ApsStrings.user_preferences,
                items = aimiWithDrillInLinks(userPreferenceItems()),
            )
        )
    }

    private fun platformItem(slot: AimiPlatformPreferenceSlot): PreferenceItem? = platformItems.item(slot)

    private fun userPreferenceItems(): List<PreferenceItem> = buildList {
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_ai_keys",
                title = ApsStrings.aimi_prefs_ai_title,
                items = listOf(
                    StringKey.AimiAdvisorProvider.withEntries(
                        mapOf(
                            "OPENAI" to ApsStrings.aimi_prefs_provider_openai,
                            "GEMINI" to ApsStrings.aimi_prefs_provider_gemini,
                            "DEEPSEEK" to ApsStrings.aimi_prefs_provider_deepseek,
                            "CLAUDE" to ApsStrings.aimi_prefs_provider_claude,
                        )
                    ),
                    StringKey.AimiAdvisorOpenAIKey,
                    StringKey.AimiAdvisorGeminiKey,
                    StringKey.AimiAdvisorDeepSeekKey,
                    StringKey.AimiAdvisorClaudeKey,
                    AimiStringKey.AimiAdvisorClaudeModel,
                    BooleanKey.OApsAIMIAdvisorPersonalOrefMl,
                    BooleanKey.OApsAIMIAdvisorLlmRichOref,
                ),
            )
        )
        // Single string pref: inline avoids an extra nested sub-screen + full-screen drill-down
        // (same UX as other lone keys, e.g. pregnancy date).
        add(AimiStringKey.RemoteControlPin)
        add(tpoSubScreen())
        add(ApsIntentKey.AimiControlCenter.withCompose(controlCenter))
        add(ApsIntentKey.AimiSettingsAudit.withCompose(settingsAudit))
        platformItem(AimiPlatformPreferenceSlot.SUPPORT_PACKAGE)?.let(::add)
        platformItem(AimiPlatformPreferenceSlot.CONTEXT)?.let(::add)
        platformItem(AimiPlatformPreferenceSlot.MEAL_ADVISOR)?.let(::add)
        platformItem(AimiPlatformPreferenceSlot.MODE_SETTINGS)?.let(::add)
        platformItem(AimiPlatformPreferenceSlot.PROFILE_ADVISOR)?.let(::add)
        pkpdGuidedSubScreen()?.let(::add)
        add(patientContextSubScreen())
        add(sosSubScreen())
        add(physioSubScreen())
        add(labSubScreen())
    }

    private fun tpoSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_tpo",
            title = ApsStrings.aimi_tpo_prefs_subscreen_title,
            // Key list declared in AimiSettingsScreens, so a test can prove it is reachable.
            items = AimiSettingsScreens.preferenceKeysOf(
                AimiSettingsSectionId.TpoEnable,
                AimiSettingsSectionId.TpoOptions,
            ),
        )

    /** Null when the platform has no PK/PD setup screen: the whole sub screen is then left out. */
    private fun pkpdGuidedSubScreen(): PreferenceSubScreenDef? =
        platformItem(AimiPlatformPreferenceSlot.PKPD_SETUP)?.let { setup ->
            PreferenceSubScreenDef(
                key = "aimi_compose_pkpd_guided",
                title = ApsStrings.aimi_pkpd_guided_title,
                items = listOf(setup),
            )
        }

    private fun patientContextSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_patient_context",
            title = ApsStrings.aimi_patient_context_title,
            items = buildList {
                add(DoubleKey.OApsAIMIweight)
                add(DoubleKey.OApsAIMICHO)
                add(DoubleKey.OApsAIMITDD7)
                add(BooleanKey.OApsAIMIpregnancy)
                add(AimiStringKey.PregnancyDueDateString)
                add(BooleanKey.OApsAIMIhoneymoon)
                add(BooleanKey.OApsAIMInight)
                add(womenCycleSubScreen())
                add(inflammatorySubScreen())
                add(thyroidModuleSubScreen())
                add(endometriosisSubScreen())
                add(nightGrowthSubScreen())
            },
        )

    private fun sosSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_sos",
            title = ApsStrings.aimi_sos_title,
            items = buildList {
                add(BooleanKey.AimiEmergencySosEnable)
                add(StringKey.AimiEmergencySosPhone)
                add(StringKey.AimiEmergencySosPhone2)
                add(IntKey.AimiEmergencySosThreshold)
                add(IntKey.AimiEmergencySosImmediateThreshold)
                add(IntKey.AimiEmergencySosStaleThreshold)
                platformItem(AimiPlatformPreferenceSlot.SOS_PERMISSIONS)?.let(::add)
                add(
                    ApsIntentKey.AimiHypoRiskAlarmInfo.withCompose(
                        ComposeScreenContent { onBack ->
                            AimiPreferenceInfoScreen(
                                title = ApsStrings.hypo_risk_notification_title,
                                message = ApsStrings.aimi_hypo_risk_alarm_summary,
                                onBack = onBack,
                            )
                        },
                    ),
                )
            },
        )

    private fun physioSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_physio",
            title = ApsStrings.aimi_physio_title,
            items = buildList {
                add(BooleanKey.AimiPhysioAssistantEnable)
                add(
                    ApsIntentKey.AimiPhysioPatternCatalogInfo.withCompose(
                        ComposeScreenContent { onBack ->
                            AimiPreferenceInfoScreen(
                                title = ApsStrings.aimi_physio_pattern_catalog_title,
                                message = ApsStrings.aimi_physio_pattern_catalog_detail,
                                onBack = onBack,
                            )
                        },
                    ),
                )
                platformItem(AimiPlatformPreferenceSlot.HEALTH_CONNECT_PERMISSIONS)?.let(::add)
                add(AimiStringKey.ActivitySourceMode)
                add(AimiStringKey.OuraPersonalAccessToken)
                add(BooleanKey.AimiPhysioSleepDataEnable)
                add(BooleanKey.AimiPhysioHRVDataEnable)
                add(BooleanKey.AimiPhysioLLMAnalysisEnable)
                add(BooleanKey.AimiHrvBaselinePerNight)
                add(BooleanKey.AimiPhysioDebugLogs)
            },
        )

    private fun labSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_lab",
            title = ApsStrings.aimi_lab_title,
            items = buildList {
                add(BooleanKey.OApsAIMIMLtraining)
                add(DoubleKey.OApsAIMISmbTrainingMinQuality)
                add(BooleanKey.OApsAIMISmbTrainingRelaxedFilter)
                platformItem(AimiPlatformPreferenceSlot.HORMONITOR_VIEWER)?.let(::add)
                add(DoubleKey.OApsAIMIMaxSMB)
                add(DoubleKey.OApsAIMIHighBGMaxSMB)
                // Added so a clinician-level value read by a dozen dosing paths has a screen home.
                add(DoubleKey.OApsAIMIHighBg)
                pkpdSubScreen()?.let(::add)
                add(adaptiveBasalSubScreen())
                add(t3cSubScreen())
                add(trajectorySubScreen())
                add(manualModesSubScreen())
                add(autodriveSubScreen())
                add(BooleanKey.OApsxdriponeminute)
                add(BooleanKey.OApsAIMIUnifiedReactivityEnabled)
                add(BooleanKey.AimiParityCaptureEnable)
                add(aiAuditorSubScreen())
            },
        )

    private fun adaptiveBasalSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_adaptive_basal",
            title = ApsStrings.oaps_aimi_adaptive_basal_title,
            // Key list declared in AimiSettingsScreens, so a test can prove it is reachable.
            items = AimiSettingsScreens.preferenceKeysOf(
                AimiSettingsSectionId.AdaptiveBasalEnable,
                AimiSettingsSectionId.AdaptiveBasalExpert,
                // Appended, not inserted, so the existing two keep their screen position.
                AimiSettingsSectionId.AdaptiveBasalLimits,
            ),
        )

    private fun t3cSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_t3c",
            title = ApsStrings.aimi_t3c_settings_title,
            // Key list declared in AimiSettingsScreens, so a test can prove it is reachable.
            items = AimiSettingsScreens.preferenceKeysOf(
                AimiSettingsSectionId.T3cMode,
                AimiSettingsSectionId.T3cAutodriveAuthority,
                AimiSettingsSectionId.T3cBehaviour,
                AimiSettingsSectionId.T3cCfrdLgsFloor,
                AimiSettingsSectionId.T3cCfrdCobDelay,
                AimiSettingsSectionId.T3cAnticipation,
            ),
        )

    private fun trajectorySubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_trajectory",
            title = ApsStrings.aimi_trajectory_section_title,
            items = buildList {
                add(BooleanKey.OApsAIMITrajectoryGuardEnabled)
                add(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)
                // Sits next to the advisor it changes. Off by default, and the only setting in this
                // screen that can raise a dose, which its own summary says in the first person.
                add(BooleanKey.OApsAIMITubeVetoIgnoreFloorArtefact)
                add(BooleanKey.OApsAIMITubeHyperClampPhysicalBound)
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_tube_mpc",
                        title = KeysStrings.aimi_tube_advanced_title,
                        items = listOf(
                            DoubleKey.AimiTubeHypoFloorMgdl,
                            DoubleKey.AimiTubeHyperBandMgdl,
                            DoubleKey.AimiTubeAggressiveness,
                            DoubleKey.AimiTubeBasalTrimMax,
                            DoubleKey.AimiTubeKappaSafetyMargin,
                        ),
                    )
                )
            },
        )

    private fun womenCycleSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_wcycle",
            title = ApsStrings.wcycle_preferences,
            items = buildList {
                add(BooleanKey.OApsAIMIwcycle)
                add(
                    StringKey.OApsAIMIWCycleTrackingMode.withEntries(
                        mapOf(
                            "FIXED_28" to ApsStrings.aimi_wcycle_tracking_fixed_28,
                            "CALENDAR_VARIABLE" to ApsStrings.aimi_wcycle_tracking_calendar_variable,
                            "NO_MENSES_LARC" to ApsStrings.aimi_wcycle_tracking_no_menses_larc,
                            "PERIMENOPAUSE" to ApsStrings.aimi_wcycle_tracking_perimenopause,
                            "MENOPAUSE" to ApsStrings.aimi_wcycle_tracking_menopause,
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIWCycleContraceptive.withEntries(
                        mapOf(
                            "NONE" to ApsStrings.aimi_wcycle_contraceptive_none,
                            "COC_PILL" to ApsStrings.aimi_wcycle_contraceptive_coc_pill,
                            "POP_PILL" to ApsStrings.aimi_wcycle_contraceptive_pop_pill,
                            "HORMONAL_IUD" to ApsStrings.aimi_wcycle_contraceptive_hormonal_iud,
                            "COPPER_IUD" to ApsStrings.aimi_wcycle_contraceptive_copper_iud,
                            "IMPLANT" to ApsStrings.aimi_wcycle_contraceptive_implant,
                            "INJECTION" to ApsStrings.aimi_wcycle_contraceptive_injection,
                            "RING" to ApsStrings.aimi_wcycle_contraceptive_ring,
                            "PATCH" to ApsStrings.aimi_wcycle_contraceptive_patch,
                        )
                    )
                )
                add(DoubleKey.OApsAIMIwcycledateday)
                add(IntKey.OApsAIMIWCycleAvgLength)
                add(BooleanKey.OApsAIMIWCycleShadow)
                add(BooleanKey.OApsAIMIWCycleRequireConfirm)
                add(DoubleKey.OApsAIMIWCycleClampMin)
                add(DoubleKey.OApsAIMIWCycleClampMax)
            },
        )

    private fun inflammatorySubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_inflammatory",
            title = ApsStrings.aimi_inflammatory_diseases_title,
            items = listOf(
                StringKey.OApsAIMIWCycleThyroid.withEntries(
                    mapOf(
                        "EUTHYROID" to ApsStrings.aimi_wcycle_thyroid_euthyroid,
                        "HYPOTHYROID_TREATED" to ApsStrings.aimi_wcycle_thyroid_hypothyroid_treated,
                        "HASHIMOTO" to ApsStrings.aimi_wcycle_thyroid_hashimoto,
                        "THYROIDECTOMY" to ApsStrings.aimi_wcycle_thyroid_thyroidectomy,
                    )
                ),
                StringKey.OApsAIMIWCycleVerneuil.withEntries(
                    mapOf(
                        "NONE" to ApsStrings.aimi_wcycle_verneuil_none,
                        "QUIESCENT" to ApsStrings.aimi_wcycle_verneuil_quiescent,
                        "ACTIVE" to ApsStrings.aimi_wcycle_verneuil_active,
                        "FLARE" to ApsStrings.aimi_wcycle_verneuil_flare,
                    )
                ),
            ),
        )

    private fun thyroidModuleSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_thyroid_module",
            title = ApsStrings.oaps_aimi_thyroid_title,
            items = buildList {
                add(BooleanKey.OApsAIMIThyroidEnabled)
                add(
                    StringKey.OApsAIMIThyroidMode.withEntries(
                        mapOf(
                            "MANUAL" to ApsStrings.aimi_thyroid_mode_manual,
                            "AUTO" to ApsStrings.aimi_thyroid_mode_auto,
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIThyroidManualStatus.withEntries(
                        mapOf(
                            "EUTHYROID" to ApsStrings.aimi_thyroid_status_euthyroid,
                            "HYPER_MILD" to ApsStrings.aimi_thyroid_status_hyper_mild,
                            "HYPER_MODERATE" to ApsStrings.aimi_thyroid_status_hyper_moderate,
                            "HYPER_SEVERE" to ApsStrings.aimi_thyroid_status_hyper_severe,
                            "NORMALIZING" to ApsStrings.aimi_thyroid_status_normalizing,
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIThyroidTreatmentPhase.withEntries(
                        mapOf(
                            "NONE" to ApsStrings.aimi_thyroid_phase_none,
                            "TITRATION" to ApsStrings.aimi_thyroid_phase_titration,
                            "STABLE" to ApsStrings.aimi_thyroid_phase_stable,
                            "DE_ESCALATION" to ApsStrings.aimi_thyroid_phase_de_escalation,
                        )
                    )
                )
                add(
                    StringKey.OApsAIMIThyroidGuardLevel.withEntries(
                        mapOf(
                            "LOW" to ApsStrings.aimi_thyroid_guard_low,
                            "MEDIUM" to ApsStrings.aimi_thyroid_guard_medium,
                            "HIGH" to ApsStrings.aimi_thyroid_guard_high,
                        )
                    )
                )
                add(BooleanKey.OApsAIMIThyroidLogVerbosity)
            },
        )

    private fun endometriosisSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_endo",
            title = ApsStrings.endo_preferences_title,
            items = listOf(
                BooleanKey.AimiEndometriosisEnable,
                BooleanKey.AimiEndometriosisPainFlare,
                IntKey.AimiEndometriosisFlareDuration,
                DoubleKey.AimiEndometriosisBasalMult,
                DoubleKey.AimiEndometriosisSmbDampen,
            ),
        )

    private fun aiAuditorSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_ai_auditor",
            title = ApsStrings.aimi_ai_auditor_section_title,
            items = buildList {
                add(BooleanKey.AimiAuditorEnabled)
                add(
                    StringKey.AimiAuditorMode.withEntries(
                        mapOf(
                            "AUDIT_ONLY" to ApsStrings.aimi_auditor_mode_audit_only,
                            "SOFT_MODULATION" to ApsStrings.aimi_auditor_mode_soft_modulation,
                            "HIGH_RISK_ONLY" to ApsStrings.aimi_auditor_mode_high_risk_only,
                        )
                    )
                )
                add(BooleanKey.OApsAIMIAuditorProfileFactors)
                add(IntKey.AimiAuditorMaxPerHour)
                add(IntKey.AimiAuditorTimeoutSeconds)
                add(IntKey.AimiAuditorMinConfidence)
            },
        )

    private fun nightGrowthSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_ngr",
            title = ApsStrings.oaps_aimi_ngr_title,
            // Key list declared in AimiSettingsScreens, so a test can prove it is reachable.
            items = AimiSettingsScreens.preferenceKeysOf(
                AimiSettingsSectionId.NightGrowthIntent,
                AimiSettingsSectionId.NightGrowthBudget,
            ),
        )

    private fun manualModesSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_training_ml_modes",
            title = ApsStrings.training_ml_modes_preferences,
            items = buildList {
                add(DoubleKey.meal_modes_MaxBasal)
                add(modeSubScreen("aimi_compose_mode_breakfast", ApsStrings.training_ml_breakfast_modes_preferences, AimiSettingsSectionId.ModeBreakfast))
                add(modeSubScreen("aimi_compose_mode_lunch", ApsStrings.training_ml_lunch_modes_preferences, AimiSettingsSectionId.ModeLunch))
                add(modeSubScreen("aimi_compose_mode_dinner", ApsStrings.training_ml_dinner_modes_preferences, AimiSettingsSectionId.ModeDinner))
                add(modeSubScreen("aimi_compose_mode_highcarb", ApsStrings.training_ml_high_carb_modes_preferences, AimiSettingsSectionId.ModeHighCarb))
                add(modeSubScreen("aimi_compose_mode_snack", ApsStrings.training_ml_snack_modes_preferences, AimiSettingsSectionId.ModeSnack))
                add(modeSubScreen("aimi_compose_mode_meal", ApsStrings.training_ml_generic_meal_modes_preferences, AimiSettingsSectionId.ModeMeal))
                add(modeSubScreen("aimi_compose_mode_sleep", ApsStrings.training_ml_sleep_modes_preferences, AimiSettingsSectionId.ModeSleep))
            },
        )

    /** One meal mode screen. Its key list is declared in [AimiSettingsScreens]. */
    private fun modeSubScreen(key: String, title: TextRef, section: AimiSettingsSectionId): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = key,
            title = title,
            items = AimiSettingsScreens.preferenceKeysOf(section),
        )

    private fun autodriveSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_autodrive",
            title = ApsStrings.autodrive_preferences,
            items = buildList {
                add(BooleanKey.OApsAIMIautoDriveActive)
                add(BooleanKey.OApsAIMIautoDriveAuthoritative)
                add(BooleanKey.OApsAIMIHyperTrajectoryRelease)
                add(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive)
                add(BooleanKey.OApsAIMIRecursiveBeliefShadow)
                add(BooleanKey.OApsAIMIRecursiveBeliefAuthority)
                add(BooleanKey.OApsAIMIRecursiveBeliefWavelet)
                add(BooleanKey.OApsAIMITreeMealRiseFrontLoad)
                add(BooleanKey.OApsAIMISensorConfidenceCgmFirst)
                add(BooleanKey.OApsAIMIMealConfirmedEarlyRelease)
                add(DoubleKey.OApsAIMIHyperEstablishedDevMgdl)
                add(DoubleKey.OApsAIMIHyperDeepDevMgdl)
                add(DoubleKey.autodriveMaxBasal)
                add(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep)
                add(BooleanKey.OApsAIMIautodriveAggressiveSmbFloor)
                add(BooleanKey.OApsAIMIStressIsfFloor)
                add(BooleanKey.OApsAIMIEffortActivityProtection)
                add(BooleanKey.OApsAIMIRiseCeilingGuard)
                add(BooleanKey.OApsAIMIAnticipBasalFloor)
                add(DoubleKey.OApsAIMIAnticipBudgetU)
                add(BooleanKey.OApsAIMIAnticipMealEvidence)
                add(DoubleKey.OApsAIMIautodrivesmallPrebolus)
                add(DoubleKey.OApsAIMIautodrivePrebolus)
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_autodrive_prebolus_vars",
                        title = ApsStrings.autodrive_prebolus_variables,
                        items = AimiSettingsScreens.preferenceKeysOf(
                            AimiSettingsSectionId.AutodrivePrebolusVariables,
                        ),
                    )
                )
            },
        )

    /** Null when the platform has no PK/PD setup screen: the whole sub screen is then left out. */
    private fun pkpdSubScreen(): PreferenceSubScreenDef? =
        platformItem(AimiPlatformPreferenceSlot.PKPD_SETUP)?.let { setup ->
            PreferenceSubScreenDef(
                key = "aimi_compose_pkpd",
                title = ApsStrings.oaps_aimi_pkpd_section_title,
                // Wave1 G1: product surface is the guided Compose screen only - raw key dump removed.
                items = listOf(setup),
            )
        }

    companion object {

        /** Key of the plugin card. */
        const val ROOT_KEY: String = "openapsaimi_settings"

        /** Key of the "User preferences" header, the only AIMI sub screen drawn inside the card. */
        const val USER_PREFERENCES_KEY: String = "aimi_user_preferences"
    }
}
