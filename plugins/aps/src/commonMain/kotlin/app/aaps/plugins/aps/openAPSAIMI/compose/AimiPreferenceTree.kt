package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.KeysStrings
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.context.ui.AimiPreferenceInfoScreen
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot

/**
 * The AIMI settings tree that `OpenAPSAIMIPlugin.getPreferenceScreenContent` returns.
 *
 * It lives outside the plugin so a test can build it with fakes: the plugin itself needs dozens of
 * services. Every page reads its AIMI keys from [AimiSettingsScreens.preferenceKeysOf], so a page
 * can never drift from what the manifest says it shows.
 *
 * Since step S2 the user preferences are organised the Trio way:
 * - the Essentials page (first row of the card, see [AimiEssentials]);
 * - one feature card per feature ([featureCards]): an on/off switch, one plain sentence, and the
 *   settings of that feature;
 * - "What AIMI learned" (read only) and "Changes" (the settings audit);
 * - the pages for the person's data, the SMB limits, the AI keys and TPO;
 * - the Expert area: every remaining EXPERT key grouped by family, the technical pages, a search.
 *
 * Everything below "User preferences" is reachable by browsing through the link rows added by
 * [aimiWithDrillInLinks]; see that function for why they are needed.
 *
 * @param platformItems entries only the platform can show; a `null` entry is left out of the tree
 * @param essentials the Essentials page
 * @param learned the "What AIMI learned" page
 * @param settingsAudit the settings audit screen
 * @param nightGrowthState the read only "Night growth support now" page of the night growth settings
 */
internal class AimiPreferenceTree(
    private val platformItems: AimiPlatformPreferenceItems,
    private val essentials: ComposeScreenContent,
    private val learned: ComposeScreenContent,
    private val settingsAudit: ComposeScreenContent,
    private val nightGrowthState: ComposeScreenContent,
) {

    /** The tree of the last [build], for the search of the Expert page. */
    private var builtRoot: PreferenceSubScreenDef? = null

    fun build(icon: ImageVector?): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = ROOT_KEY,
            title = ApsStrings.openapsaimi,
            icon = icon,
            items = rootItems(),
        ).also { builtRoot = it }

    /**
     * The Essentials page link, the OpenAPS/SMB core keys (same order as `OpenAPSSMBPlugin`), then the
     * nested AIMI sections.
     */
    private fun rootItems(): List<PreferenceItem> = buildList {
        add(AimiEssentialsLink(screen = AimiEssentials.screenDef(), content = essentials))
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
        val cards = featureCards(platformItems, nightGrowthState).associateBy { it.id.screenKey }
        add(
            PreferenceSubScreenDef(
                key = USER_PREFERENCES_KEY,
                title = ApsStrings.user_preferences,
                items = aimiWithDrillInLinks(userPreferenceItems()) { screen -> pageFor(screen, cards) },
            )
        )
    }

    /** The page a link opens when it is more than the plain list: a feature card or the Expert area. */
    private fun pageFor(screen: PreferenceSubScreenDef, cards: Map<String, AimiFeatureCard>): ComposeScreenContent? {
        cards[screen.key]?.let { card ->
            return ComposeScreenContent { onBack -> AimiFeatureCardPage(card = card, screen = screen, onBack = onBack) }
        }
        if (screen.key == EXPERT_KEY) {
            return ComposeScreenContent { onBack ->
                AimiExpertPage(
                    screen = screen,
                    searchEntries = { builtRoot?.let(AimiSettingsSearch::index).orEmpty() },
                    openResult = { entry -> searchResultPage(entry, cards) },
                    onBack = onBack,
                )
            }
        }
        return null
    }

    /**
     * The page a search result opens, with the row of the result highlighted. A key on a feature
     * card opens the card page, so the card sentence and the "turn it on" hint are drawn and a row
     * that has no effect while the switch is off stays hidden.
     */
    private fun searchResultPage(entry: AimiSettingsSearchEntry, cards: Map<String, AimiFeatureCard>): ComposeScreenContent {
        val card = cards[entry.screen.key]
        return ComposeScreenContent { onBack ->
            if (card != null) {
                AimiFeatureCardPage(card = card, screen = entry.screen, onBack = onBack, highlightKey = entry.row.key)
            } else {
                AimiPreferenceSubScreen(screen = entry.screen, onBack = onBack, highlightKey = entry.row.key)
            }
        }
    }

    private fun platformItem(slot: AimiPlatformPreferenceSlot): PreferenceItem? = platformItems.item(slot)

    private fun userPreferenceItems(): List<PreferenceItem> = buildList {
        featureCards(platformItems, nightGrowthState).forEach { add(it.screen) }
        add(ApsIntentKey.AimiLearned.withCompose(learned))
        add(ApsIntentKey.AimiSettingsAudit.withCompose(settingsAudit))
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_patient_context",
                title = ApsStrings.aimi_patient_about_you_title,
                items = shown(
                    AimiSettingsSectionId.PatientBody,
                    AimiSettingsSectionId.PatientPregnancyDueDate,
                    AimiSettingsSectionId.PatientHoneymoon,
                ),
            )
        )
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_dose_limits",
                title = ApsStrings.aimi_essentials_group_limits,
                items = shown(AimiSettingsSectionId.DoseLimits),
            )
        )
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_ai_keys",
                title = ApsStrings.aimi_prefs_ai_title,
                items = shown(AimiSettingsSectionId.AiAdvisorKeys, AimiSettingsSectionId.PreferencesAiAdvisorLearning),
            )
        )
        // Single string pref: inline avoids an extra nested sub-screen + full-screen drill-down.
        addAll(shown(AimiSettingsSectionId.PreferencesRootRemotePin))
        add(
            PreferenceSubScreenDef(
                key = "aimi_compose_tpo",
                title = ApsStrings.aimi_tpo_prefs_subscreen_title,
                items = shown(AimiSettingsSectionId.TpoEnable, AimiSettingsSectionId.TpoOptions),
            )
        )
        platformItem(AimiPlatformPreferenceSlot.SUPPORT_PACKAGE)?.let(::add)
        platformItem(AimiPlatformPreferenceSlot.CONTEXT)?.let(::add)
        platformItem(AimiPlatformPreferenceSlot.PROFILE_ADVISOR)?.let(::add)
        add(expertSubScreen())
    }

    /**
     * The Expert area: the family pages generated from the manifest, then the technical pages, then
     * the hormone study viewer. The warning and the search are drawn by [AimiExpertPage].
     */
    private fun expertSubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = EXPERT_KEY,
            title = ApsStrings.aimi_expert_title,
            summary = ApsStrings.aimi_expert_summary,
            items = buildList {
                AimiBehaviorFamilyId.entries.forEach { family ->
                    val keys = shown(AimiSettingsScreens.expertSectionIdOf(family))
                    if (keys.isNotEmpty()) {
                        add(PreferenceSubScreenDef(key = EXPERT_FAMILY_KEY_PREFIX + family.name, title = family.title(), items = keys))
                    }
                }
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_adaptive_basal",
                        title = ApsStrings.oaps_aimi_adaptive_basal_title,
                        items = shown(
                            AimiSettingsSectionId.AdaptiveBasalEnable,
                            AimiSettingsSectionId.AdaptiveBasalExpert,
                            AimiSettingsSectionId.AdaptiveBasalLimits,
                        ),
                    )
                )
                add(trajectorySubScreen())
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_ai_auditor",
                        title = ApsStrings.aimi_ai_auditor_section_title,
                        items = shown(AimiSettingsSectionId.AuditorSettings, AimiSettingsSectionId.AuditorProfileFactors),
                    )
                )
                platformItem(AimiPlatformPreferenceSlot.HORMONITOR_VIEWER)?.let(::add)
            },
        )

    private fun trajectorySubScreen(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = "aimi_compose_trajectory",
            title = ApsStrings.aimi_trajectory_section_title,
            items = buildList {
                addAll(shown(AimiSettingsSectionId.TrajectoryGuards))
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_prediction_guards",
                        title = ApsStrings.aimi_prediction_guards_title,
                        items = shown(AimiSettingsSectionId.TrajectoryPredictionGuards),
                    )
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_tube_mpc",
                        title = KeysStrings.aimi_tube_advanced_title,
                        items = shown(AimiSettingsSectionId.TubeAdvancedTunables),
                    )
                )
            },
        )

    companion object {

        /** Key of the plugin card. */
        const val ROOT_KEY: String = "openapsaimi_settings"

        /** Key of the "User preferences" header, the only AIMI sub screen drawn inside the card. */
        const val USER_PREFERENCES_KEY: String = "aimi_user_preferences"

        /** Key of the Expert area. */
        const val EXPERT_KEY: String = "aimi_compose_expert"

        /** Key of the page on the Meals card that holds one sub page per meal mode. */
        const val MEAL_MODE_VALUES_KEY: String = "aimi_compose_meal_mode_values"

        /** Key of an Expert family page, followed by the family name. */
        const val EXPERT_FAMILY_KEY_PREFIX: String = "aimi_compose_expert_"

        /**
         * The nine feature cards, in screen order. A platform entry that is `null` is left out of
         * its card. The keys come from the card sections of [AimiSettingsScreens].
         *
         * @param nightGrowthState the read only "Night growth support now" page of the night card
         */
        fun featureCards(platformItems: AimiPlatformPreferenceItems, nightGrowthState: ComposeScreenContent): List<AimiFeatureCard> {
            fun platform(slot: AimiPlatformPreferenceSlot): PreferenceItem? = platformItems.item(slot)
            return listOf(
                AimiFeatureCard(
                    id = AimiFeatureCardId.Meals,
                    title = ApsStrings.aimi_card_meals_title,
                    sentence = ApsStrings.aimi_card_meals_sentence,
                    master = null,
                    // One page holds the values of every meal mode (one sub page per mode), so search
                    // can open a value and highlight it. The guided mode screen next to it starts a
                    // mode; it also shows and edits the values of its four modes before the start, so
                    // the person sees the prebolus before confirming. The owner decided to keep it so
                    // (PLAN.md, step D1).
                    items = buildList {
                        addAll(shown(AimiSettingsSectionId.ManualModesMaxBasal))
                        platform(AimiPlatformPreferenceSlot.MODE_SETTINGS)?.let(::add)
                        add(
                            PreferenceSubScreenDef(
                                key = MEAL_MODE_VALUES_KEY,
                                title = ApsStrings.aimi_card_meals_mode_values_title,
                                items = listOf(
                                    modeScreen("aimi_compose_mode_breakfast", ApsStrings.training_ml_breakfast_modes_preferences, AimiSettingsSectionId.ModeBreakfast),
                                    modeScreen("aimi_compose_mode_lunch", ApsStrings.training_ml_lunch_modes_preferences, AimiSettingsSectionId.ModeLunch),
                                    modeScreen("aimi_compose_mode_dinner", ApsStrings.training_ml_dinner_modes_preferences, AimiSettingsSectionId.ModeDinner),
                                    modeScreen("aimi_compose_mode_highcarb", ApsStrings.training_ml_high_carb_modes_preferences, AimiSettingsSectionId.ModeHighCarb),
                                    modeScreen("aimi_compose_mode_snack", ApsStrings.training_ml_snack_modes_preferences, AimiSettingsSectionId.ModeSnack),
                                    modeScreen("aimi_compose_mode_meal", ApsStrings.training_ml_generic_meal_modes_preferences, AimiSettingsSectionId.ModeMeal),
                                    modeScreen("aimi_compose_mode_sleep", ApsStrings.training_ml_sleep_modes_preferences, AimiSettingsSectionId.ModeSleep),
                                ),
                            )
                        )
                        addAll(shown(AimiSettingsSectionId.MealsAnticipation))
                        platform(AimiPlatformPreferenceSlot.MEAL_ADVISOR)?.let(::add)
                    },
                ),
                AimiFeatureCard(
                    id = AimiFeatureCardId.Activity,
                    title = ApsStrings.aimi_card_activity_title,
                    sentence = ApsStrings.aimi_card_activity_sentence,
                    master = null,
                    items = shown(AimiSettingsSectionId.ActivityProtection, AimiSettingsSectionId.ActivityBasalCap),
                ),
                AimiFeatureCard(
                    id = AimiFeatureCardId.Night,
                    title = ApsStrings.aimi_card_night_title,
                    sentence = ApsStrings.aimi_card_night_sentence,
                    // Night growth has its own switch and works without the night mode switch.
                    master = null,
                    items = shown(AimiSettingsSectionId.NightMode) + PreferenceSubScreenDef(
                        key = "aimi_compose_ngr",
                        title = ApsStrings.oaps_aimi_ngr_title,
                        // The state row first: it says in full what the loop does and why. The switch
                        // row shows the same state since step A1 (`AimiNightGrowthSwitch.row`, on every
                        // renderer), but the age row shows its key default (14) also when no age is stored.
                        items = listOf(ApsIntentKey.AimiNightGrowthState.withCompose(nightGrowthState)) +
                            shown(AimiSettingsSectionId.NightGrowthAge, AimiSettingsSectionId.NightGrowthIntent, AimiSettingsSectionId.NightGrowthBudget),
                    ),
                ),
                womenCycleCard(),
                AimiFeatureCard(
                    id = AimiFeatureCardId.Illness,
                    title = ApsStrings.aimi_card_illness_title,
                    sentence = ApsStrings.aimi_card_illness_sentence,
                    master = null,
                    items = shown(AimiSettingsSectionId.InflammatoryContext) + listOf(
                        PreferenceSubScreenDef(
                            key = "aimi_compose_thyroid_module",
                            title = ApsStrings.oaps_aimi_thyroid_title,
                            items = shown(AimiSettingsSectionId.ThyroidModule, AimiSettingsSectionId.ThyroidDebug),
                        ),
                        PreferenceSubScreenDef(
                            key = "aimi_compose_endo",
                            title = ApsStrings.endo_preferences_title,
                            // Owner decision of step D1: the contraceptive gets one more row here, after
                            // the endometriosis switches, because `EndometriosisAdjuster` reads it with
                            // only the endometriosis switch on. Same key; its home section stays on the
                            // women's cycle card, so the search still opens that card.
                            items = shown(AimiSettingsSectionId.EndoStatus) +
                                withChoiceLabels(StringKey.OApsAIMIWCycleContraceptive) +
                                shown(AimiSettingsSectionId.EndoTuning),
                        ),
                        // Brittle (type 3c) mode, with the CFRD settings.
                        PreferenceSubScreenDef(
                            key = "aimi_compose_t3c",
                            title = ApsStrings.aimi_t3c_settings_title,
                            items = shown(
                                AimiSettingsSectionId.T3cMode,
                                AimiSettingsSectionId.T3cAutodriveAuthority,
                                AimiSettingsSectionId.T3cBehaviour,
                                AimiSettingsSectionId.T3cCfrdLgsFloor,
                                AimiSettingsSectionId.T3cCfrdCobDelay,
                                AimiSettingsSectionId.T3cAnticipation,
                            ),
                        ),
                    ),
                ),
                AimiFeatureCard(
                    id = AimiFeatureCardId.PkpdLearning,
                    title = ApsStrings.aimi_card_pkpd_title,
                    sentence = ApsStrings.aimi_card_pkpd_sentence,
                    master = BooleanKey.OApsAIMIPkpdEnabled,
                    // The one entry point of the guided PK/PD screen. It stays shown with PK/PD off:
                    // the DIA and peak governors on its Expert tab also act then.
                    items = listOfNotNull(platform(AimiPlatformPreferenceSlot.PKPD_SETUP)),
                ),
                AimiFeatureCard(
                    id = AimiFeatureCardId.Physio,
                    title = ApsStrings.aimi_card_physio_title,
                    sentence = ApsStrings.aimi_card_physio_sentence,
                    master = BooleanKey.AimiPhysioAssistantEnable,
                    // Nothing is hidden: the sources and the tree also act with the assistant off.
                    items = buildList {
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
                        platform(AimiPlatformPreferenceSlot.HEALTH_CONNECT_PERMISSIONS)?.let(::add)
                        addAll(
                            shown(
                                AimiSettingsSectionId.PhysioDataUse,
                                AimiSettingsSectionId.PhysioSources,
                                AimiSettingsSectionId.PhysioAiAnalysis,
                                AimiSettingsSectionId.PhysioStressFloor,
                                AimiSettingsSectionId.PhysioSensorConfidence,
                            )
                        )
                    },
                ),
                autodriveCard(),
                sosCard(platform(AimiPlatformPreferenceSlot.SOS_PERMISSIONS)),
            )
        }

        private fun womenCycleCard(): AimiFeatureCard {
            val items = shown(AimiSettingsSectionId.WomenCycleIntent, AimiSettingsSectionId.WomenCycleExpert)
                .filterNot { it is PreferenceKey && it.key == BooleanKey.OApsAIMIwcycle.key }
            return AimiFeatureCard(
                id = AimiFeatureCardId.WomenCycle,
                title = ApsStrings.aimi_card_wcycle_title,
                sentence = ApsStrings.aimi_card_wcycle_sentence,
                master = BooleanKey.OApsAIMIwcycle,
                items = items,
                // `WCycleAdjuster.getInfo` returns the neutral result first when the switch is off.
                // The contraceptive stays shown: `EndometriosisAdjuster.calculateFactors` reads it with
                // only the endometriosis switch on, so it changes insulin also with the cycle off.
                dependents = items.filterIsInstance<PreferenceKey>().map { it.key }.toSet() -
                    StringKey.OApsAIMIWCycleContraceptive.key,
            )
        }

        private fun autodriveCard(): AimiFeatureCard {
            val items = buildList {
                addAll(
                    shown(
                        AimiSettingsSectionId.AutodriveChannel,
                        AimiSettingsSectionId.AutodriveBeliefShadow,
                        AimiSettingsSectionId.AutodriveBeliefAuthority,
                        AimiSettingsSectionId.AutodriveBeliefWavelet,
                        AimiSettingsSectionId.AutodriveMealRise,
                        AimiSettingsSectionId.AutodriveMaxBasal,
                        AimiSettingsSectionId.AutodriveGestures,
                    ).filterNot { it is PreferenceKey && it.key == BooleanKey.OApsAIMIautoDriveActive.key }
                )
                add(
                    PreferenceSubScreenDef(
                        key = "aimi_compose_autodrive_prebolus_vars",
                        title = ApsStrings.autodrive_prebolus_variables,
                        items = shown(AimiSettingsSectionId.AutodrivePrebolusVariables),
                    )
                )
            }
            return AimiFeatureCard(
                id = AimiFeatureCardId.Autodrive,
                title = ApsStrings.aimi_card_autodrive_title,
                sentence = ApsStrings.aimi_card_autodrive_sentence,
                master = BooleanKey.OApsAIMIautoDriveActive,
                items = items,
                // `HyperTrajectoryReleasePreferences.from` reads them only with Autodrive on. The other
                // rows stay shown: some of them are read with Autodrive off too.
                dependents = setOf(
                    BooleanKey.OApsAIMIHyperTrajectoryRelease.key,
                    BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive.key,
                ),
            )
        }

        private fun sosCard(permissions: PreferenceItem?): AimiFeatureCard {
            val dependentRows = shown(AimiSettingsSectionId.SosThresholds, AimiSettingsSectionId.SosContacts) + listOfNotNull(permissions)
            return AimiFeatureCard(
                id = AimiFeatureCardId.SafetyAlarms,
                title = ApsStrings.aimi_card_sos_title,
                sentence = ApsStrings.aimi_card_sos_sentence,
                master = BooleanKey.AimiEmergencySosEnable,
                items = dependentRows + ApsIntentKey.AimiHypoRiskAlarmInfo.withCompose(
                    ComposeScreenContent { onBack ->
                        AimiPreferenceInfoScreen(
                            title = ApsStrings.hypo_risk_notification_title,
                            message = ApsStrings.aimi_hypo_risk_alarm_summary,
                            onBack = onBack,
                        )
                    },
                ),
                // The SOS check on Android and iOS stops first when the switch is off.
                dependents = dependentRows.filterIsInstance<PreferenceKey>().map { it.key }.toSet(),
            )
        }

        /** One meal mode page. Its key list is declared in [AimiSettingsScreens]. */
        private fun modeScreen(key: String, title: TextRef, section: AimiSettingsSectionId): PreferenceSubScreenDef =
            PreferenceSubScreenDef(key = key, title = title, items = shown(section))

        /**
         * The keys of these sections as rows: a choice key with the labels of its choices, the night
         * growth switch with its own row ([AimiNightGrowthSwitch.row]).
         */
        private fun shown(vararg ids: AimiSettingsSectionId): List<PreferenceItem> =
            AimiSettingsScreens.preferenceKeysOf(*ids).map(::withChoiceLabels)

        /**
         * [key] with the labels of its choices, the night growth switch with its own row, or [key]
         * itself.
         */
        private fun withChoiceLabels(key: PreferenceKey): PreferenceItem = when (key) {
            BooleanKey.OApsAIMINightGrowthEnabled -> AimiNightGrowthSwitch.row(BooleanKey.OApsAIMINightGrowthEnabled)
            StringKey.AimiAdvisorProvider -> StringKey.AimiAdvisorProvider.withEntries(
                mapOf(
                    "OPENAI" to ApsStrings.aimi_prefs_provider_openai,
                    "GEMINI" to ApsStrings.aimi_prefs_provider_gemini,
                    "DEEPSEEK" to ApsStrings.aimi_prefs_provider_deepseek,
                    "CLAUDE" to ApsStrings.aimi_prefs_provider_claude,
                ),
            )
            StringKey.OApsAIMIWCycleTrackingMode -> StringKey.OApsAIMIWCycleTrackingMode.withEntries(
                mapOf(
                    "FIXED_28" to ApsStrings.aimi_wcycle_tracking_fixed_28,
                    "CALENDAR_VARIABLE" to ApsStrings.aimi_wcycle_tracking_calendar_variable,
                    "NO_MENSES_LARC" to ApsStrings.aimi_wcycle_tracking_no_menses_larc,
                    "PERIMENOPAUSE" to ApsStrings.aimi_wcycle_tracking_perimenopause,
                    "MENOPAUSE" to ApsStrings.aimi_wcycle_tracking_menopause,
                ),
            )
            StringKey.OApsAIMIWCycleContraceptive -> StringKey.OApsAIMIWCycleContraceptive.withEntries(
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
                ),
            )
            StringKey.OApsAIMIWCycleThyroid -> StringKey.OApsAIMIWCycleThyroid.withEntries(
                mapOf(
                    "EUTHYROID" to ApsStrings.aimi_wcycle_thyroid_euthyroid,
                    "HYPOTHYROID_TREATED" to ApsStrings.aimi_wcycle_thyroid_hypothyroid_treated,
                    "HASHIMOTO" to ApsStrings.aimi_wcycle_thyroid_hashimoto,
                    "THYROIDECTOMY" to ApsStrings.aimi_wcycle_thyroid_thyroidectomy,
                ),
            )
            StringKey.OApsAIMIWCycleVerneuil -> StringKey.OApsAIMIWCycleVerneuil.withEntries(
                mapOf(
                    "NONE" to ApsStrings.aimi_wcycle_verneuil_none,
                    "QUIESCENT" to ApsStrings.aimi_wcycle_verneuil_quiescent,
                    "ACTIVE" to ApsStrings.aimi_wcycle_verneuil_active,
                    "FLARE" to ApsStrings.aimi_wcycle_verneuil_flare,
                ),
            )
            StringKey.OApsAIMIThyroidMode -> StringKey.OApsAIMIThyroidMode.withEntries(
                mapOf(
                    "MANUAL" to ApsStrings.aimi_thyroid_mode_manual,
                    "AUTO" to ApsStrings.aimi_thyroid_mode_auto,
                ),
            )
            StringKey.OApsAIMIThyroidManualStatus -> StringKey.OApsAIMIThyroidManualStatus.withEntries(
                mapOf(
                    "EUTHYROID" to ApsStrings.aimi_thyroid_status_euthyroid,
                    "HYPER_MILD" to ApsStrings.aimi_thyroid_status_hyper_mild,
                    "HYPER_MODERATE" to ApsStrings.aimi_thyroid_status_hyper_moderate,
                    "HYPER_SEVERE" to ApsStrings.aimi_thyroid_status_hyper_severe,
                    "NORMALIZING" to ApsStrings.aimi_thyroid_status_normalizing,
                ),
            )
            StringKey.OApsAIMIThyroidTreatmentPhase -> StringKey.OApsAIMIThyroidTreatmentPhase.withEntries(
                mapOf(
                    "NONE" to ApsStrings.aimi_thyroid_phase_none,
                    "TITRATION" to ApsStrings.aimi_thyroid_phase_titration,
                    "STABLE" to ApsStrings.aimi_thyroid_phase_stable,
                    "DE_ESCALATION" to ApsStrings.aimi_thyroid_phase_de_escalation,
                ),
            )
            StringKey.OApsAIMIThyroidGuardLevel -> StringKey.OApsAIMIThyroidGuardLevel.withEntries(
                mapOf(
                    "LOW" to ApsStrings.aimi_thyroid_guard_low,
                    "MEDIUM" to ApsStrings.aimi_thyroid_guard_medium,
                    "HIGH" to ApsStrings.aimi_thyroid_guard_high,
                ),
            )
            StringKey.AimiAuditorMode -> StringKey.AimiAuditorMode.withEntries(
                mapOf(
                    "AUDIT_ONLY" to ApsStrings.aimi_auditor_mode_audit_only,
                    "SOFT_MODULATION" to ApsStrings.aimi_auditor_mode_soft_modulation,
                    "HIGH_RISK_ONLY" to ApsStrings.aimi_auditor_mode_high_risk_only,
                ),
            )
            else -> key
        }
    }
}
