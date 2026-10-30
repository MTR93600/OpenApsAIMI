package app.aaps.appshell.navigation

import androidx.navigation.NavController
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.AimiAuditorReport
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionResult
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.compose.ScreenMode
import app.aaps.core.ui.compose.navigation.GlassDestination
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.search.SearchableItem
import app.aaps.ui.compose.careDialog.CareportalEventType
import app.aaps.ui.compose.fillDialog.FillPreselect
import app.aaps.ui.compose.main.MainViewModel
import app.aaps.ui.compose.quickLaunch.QuickLaunchAction
import app.aaps.ui.search.SearchIndexEntry

/**
 * Where a tap in the drawer, the toolbar or a search result actually goes.
 *
 * This was inline in `ComposeMainActivity` and nothing in it was Android: it is a mapping from
 * [ElementType] to a route, plus the protection check that has to pass first. Leaving it there meant
 * every other platform had a drawer whose items did nothing - which is exactly what desktop showed
 * the moment the overview was hosted there.
 *
 * Two things genuinely are platform specific and are passed in: launching another app for a CGM, and
 * closing the app. Everything else is shared.
 *
 * @param onOpenCgmApp opens the CGM app by package name. Android only; elsewhere there is no app to
 *   open, and the caller should say so rather than doing nothing quietly.
 * @param onExit closes the window or activity. Called **before** `configBuilder.exitApp`, which is
 *   what the phone did: the UI goes first, then the app records the exit and stops the process.
 * @param auditorReport opens the AIMI auditor report. Null where the graph has no AIMI (desktop);
 *   there the Glass tile that sends [NavigationRequest.AuditorReport] is disabled.
 */
@Suppress("LongParameterList")
class ElementNavigator(
    val navController: NavController,
    val mainViewModel: MainViewModel,
    val activePlugin: ActivePlugin,
    val protectionCheck: ProtectionCheck,
    private val configBuilder: ConfigBuilder,
    private val dexcomBoyda: DexcomBoyda,
    private val auditorReport: AimiAuditorReport?,
    private val onOpenCgmApp: (packageName: String) -> Unit,
    private val onExit: () -> Unit,
    val onRequestDirectoryAccess: () -> Unit,
    val onOpenUrl: (url: String) -> Unit
) {

    /** Where a tap in the drawer, a search result or a quick launch tile goes. */
    fun handleNavigationRequest(request: NavigationRequest) {
        when (request) {
            is NavigationRequest.Element           -> navigateProtected(request.type)

            is NavigationRequest.QuickWizard       -> guarded(ElementType.QUICK_WIZARD.protection) {
                mainViewModel.executeQuickWizard(request.guid)
            }

            is NavigationRequest.Plugin            -> {
                val plugin = activePlugin.getPluginsList()
                    .find { it::class.simpleName == request.className } ?: return
                openPlugin(plugin, navController, activePlugin)
            }

            // The information screens need no protection, the treatment and loop screens BOLUS, as on dev_OAPSAIMI.
            // The role and "master or paired client" rules are checked here, not only by the caller, so a
            // new way to open a treatment screen cannot skip them.
            is NavigationRequest.Glass             -> {
                val allowed = request.destination.isAllowed(
                    masterOrPairedClient = mainViewModel.masterOrPairedClient.value,
                    clientRole = mainViewModel.clientRole.value,
                )
                if (allowed) guarded(request.destination.protection) {
                    navController.navigate(glassRoute(request.destination))
                }
            }

            // A dialog with text only, no protection, as on dev_OAPSAIMI.
            NavigationRequest.AuditorReport        -> auditorReport?.openReport()

            is NavigationRequest.PluginPreferences -> guarded(request.requiredProtection) {
                navController.navigate(
                    AppRoute.PluginPreferences.createRoute(
                        request.pluginKey,
                        request.composeScreenKey,
                    ),
                )
            }
        }
    }

    /**
     * Asks for authorization when the element needs it, then navigates.
     *
     * The granted level decides the mode: anything at or above "preferences" opens a management screen
     * for editing, anything less opens it read only. That is why the level is carried through rather
     * than just a yes or no.
     */
    @Suppress("LongParameterList")
    private fun navigateProtected(elementType: ElementType) {
        fun go(mode: ScreenMode) = navigateToElement(elementType, mode)

        val minLevel = elementType.protection
        if (minLevel == ProtectionCheck.Protection.NONE) {
            go(ScreenMode.EDIT)
            return
        }
        protectionCheck.requestAuthorization(minLevel) { result ->
            result.grantedLevel?.let { granted ->
                val mode = if (granted.level >= ProtectionCheck.Protection.PREFERENCES.level) ScreenMode.EDIT else ScreenMode.PLAY
                go(mode)
            }
        }
    }

    /** Runs [action] once the protection level is granted, or straight away when none is needed. */
    fun guarded(protection: ProtectionCheck.Protection, action: () -> Unit) {
        when (protection) {
            ProtectionCheck.Protection.NONE        -> action()
            ProtectionCheck.Protection.BOLUS,
            ProtectionCheck.Protection.APPLICATION,
            ProtectionCheck.Protection.MASTER,
            ProtectionCheck.Protection.PREFERENCES -> protectionCheck.requestProtection(protection) { result ->
                if (result == ProtectionResult.GRANTED) action()
            }
        }
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun navigateToElement(elementType: ElementType, mode: ScreenMode) {
        when (elementType) {
            // Navigation screens (drawer)
            ElementType.TREATMENTS              -> navController.navigate(AppRoute.Treatments.route)
            ElementType.STATISTICS,
            ElementType.TDD_CYCLE_PATTERN       -> navController.navigate(AppRoute.Stats.route)

            ElementType.PROFILE_HELPER          -> navController.navigate(AppRoute.ProfileHelper.route)
            ElementType.HISTORY_BROWSER         -> navController.navigate(AppRoute.HistoryBrowser.route)
            ElementType.SETUP_WIZARD            -> navController.navigate(AppRoute.SetupWizard.route)
            ElementType.MAINTENANCE             -> mainViewModel.setShowMaintenanceSheet(true)
            ElementType.CONFIGURATION           -> navController.navigate(AppRoute.Configuration.route)
            ElementType.ABOUT                   -> mainViewModel.setShowAboutDialog(true)

            // Management screens - mode determined by granted auth level
            ElementType.INSULIN_MANAGEMENT      -> navController.navigate(AppRoute.InsulinManagement.createRoute(mode))
            ElementType.PROFILE_MANAGEMENT      -> navController.navigate(AppRoute.Profile.createRoute(mode))
            ElementType.TEMP_TARGET_MANAGEMENT  -> navController.navigate(AppRoute.TempTargetManagement.createRoute(mode))
            ElementType.QUICK_WIZARD_MANAGEMENT -> navController.navigate(AppRoute.QuickWizardManagement.createRoute(mode))
            ElementType.FOOD_MANAGEMENT         -> navController.navigate(AppRoute.FoodManagement.route)
            ElementType.RUNNING_MODE            -> navController.navigate(AppRoute.RunningMode.route)
            ElementType.SCENE_MANAGEMENT        -> navController.navigate(AppRoute.SceneList.createRoute(mode))
            ElementType.AUTOMATION_MANAGEMENT   -> navController.navigate(AppRoute.AutomationList.route)
            ElementType.AUTHORIZED_CLIENTS      -> navController.navigate(AppRoute.AuthorizedClients.route)
            ElementType.PAIR_WITH_MASTER        -> navController.navigate(AppRoute.PairWithMaster.createRoute())
            ElementType.QUICK_LAUNCH_CONFIG     -> navController.navigate(AppRoute.QuickLaunchConfig.route)

            // Treatment dialogs
            ElementType.CARBS                   -> navController.navigate(AppRoute.CarbsDialog.route)
            ElementType.INSULIN                 -> navController.navigate(AppRoute.InsulinDialog.route)
            ElementType.TREATMENT               -> navController.navigate(AppRoute.TreatmentDialog.route)
            ElementType.FILL                    -> navController.navigate(AppRoute.FillDialog.createRoute(FillPreselect.CARTRIDGE_CHANGE.ordinal))
            ElementType.CANNULA_CHANGE          -> navController.navigate(AppRoute.FillDialog.createRoute(FillPreselect.SITE_CHANGE.ordinal))
            ElementType.BOLUS_WIZARD            -> navController.navigate(AppRoute.WizardDialog.createRoute())
            ElementType.TEMP_BASAL              -> navController.navigate(AppRoute.TempBasalDialog.route)
            ElementType.EXTENDED_BOLUS          -> navController.navigate(AppRoute.ExtendedBolusDialog.route)

            // CGM
            ElementType.CGM_XDRIP               -> onOpenCgmApp("com.eveningoutpost.dexdrip")
            ElementType.CGM_DEX                 -> dexcomBoyda.dexcomPackages().forEach { onOpenCgmApp(it) }

            ElementType.CALIBRATION             -> navController.navigate(AppRoute.CalibrationDialog.route)

            // Careportal
            ElementType.BG_CHECK                -> navController.navigate(AppRoute.CareDialog.createRoute(CareportalEventType.BGCHECK.ordinal))
            ElementType.SENSOR_INSERT           -> navController.navigate(AppRoute.CareDialog.createRoute(CareportalEventType.SENSOR_INSERT.ordinal))
            ElementType.BATTERY_CHANGE          -> navController.navigate(AppRoute.CareDialog.createRoute(CareportalEventType.BATTERY_CHANGE.ordinal))
            ElementType.NOTE                    -> navController.navigate(AppRoute.CareDialog.createRoute(CareportalEventType.NOTE.ordinal))
            ElementType.EXERCISE                -> navController.navigate(AppRoute.CareDialog.createRoute(CareportalEventType.EXERCISE.ordinal))
            ElementType.QUESTION                -> navController.navigate(AppRoute.CareDialog.createRoute(CareportalEventType.QUESTION.ordinal))
            ElementType.ANNOUNCEMENT            -> navController.navigate(AppRoute.CareDialog.createRoute(CareportalEventType.ANNOUNCEMENT.ordinal))
            ElementType.SITE_ROTATION           -> navController.navigate(AppRoute.SiteRotationManagement.route)

            // Settings
            ElementType.SETTINGS                -> navController.navigate(AppRoute.Preferences.route)

            // App lifecycle
            ElementType.EXIT                    -> {
                onExit()
                configBuilder.exitApp("Menu", Sources.Aaps, false)
            }

            ElementType.PUMP                    -> openPlugin(activePlugin.activePumpInternal as PluginBase, navController, activePlugin)

            // Non-searchable types - listed explicitly so the compiler catches new enum values
            ElementType.QUICK_WIZARD,
            ElementType.SCENE,
            ElementType.AUTOMATION,
            ElementType.COB,
            ElementType.SENSITIVITY,
            ElementType.USER_ENTRY,
            ElementType.LOOP,
            ElementType.AAPS                    -> {
            }
        }
    }
}



/** The registered route (the template) of a Glass dashboard screen. */
fun glassAppRoute(destination: GlassDestination): AppRoute = when (destination) {
    GlassDestination.LOOP_DASHBOARD       -> AppRoute.GlassLoopDashboard
    GlassDestination.SENSOR_QUALITY       -> AppRoute.GlassSensorQuality
    GlassDestination.PUMP_DETAIL          -> AppRoute.GlassPumpDetail
    GlassDestination.BATTERY_DETAIL       -> AppRoute.GlassBatteryDetail
    GlassDestination.INSULIN_DETAIL       -> AppRoute.GlassInsulinDetail
    GlassDestination.CANNULA_DETAIL       -> AppRoute.GlassCannulaDetail
    GlassDestination.BASAL_DETAIL         -> AppRoute.GlassBasalDetail
    GlassDestination.SENSOR_INSERT_DETAIL -> AppRoute.GlassSensorInsertDetail
    GlassDestination.TARGET_DETAIL        -> AppRoute.GlassTargetDetail
    GlassDestination.LOOP_DETAIL          -> AppRoute.GlassLoopDetail
}

/**
 * The route to navigate to for a Glass dashboard screen. The cannula screen opens with nothing
 * selected, as on `dev_OAPSAIMI`: the user picks prime, site change or cartridge change there. The
 * sensor insert screen is the care dialog for a sensor insert.
 */
fun glassRoute(destination: GlassDestination): String = when (destination) {
    GlassDestination.CANNULA_DETAIL       -> AppRoute.GlassCannulaDetail.createRoute(FillPreselect.NONE.ordinal)
    GlassDestination.SENSOR_INSERT_DETAIL -> AppRoute.GlassSensorInsertDetail.createRoute(CareportalEventType.SENSOR_INSERT.ordinal)
    else                                  -> glassAppRoute(destination).route
}

/** Opens a plugin's own screen, when it has one. Public because search results reach a plugin directly. */
fun openPlugin(plugin: PluginBase, navController: NavController, activePlugin: ActivePlugin) {
    val pluginIndex = activePlugin.getPluginsList().indexOf(plugin)
    if (plugin.hasComposeContent()) navController.navigate(AppRoute.PluginContent.createRoute(pluginIndex))
}


/**
 * A quick launch tile.
 *
 * Static tiles are ordinary navigation; the rest execute something and go through the same
 * protection check first, because a tile is a shortcut to an action, not permission to skip it.
 */
fun ElementNavigator.handleQuickLaunchAction(action: QuickLaunchAction) {
    when (action) {
        is QuickLaunchAction.StaticAction      -> handleNavigationRequest(NavigationRequest.Element(action.elementType))

        is QuickLaunchAction.QuickWizardAction -> guarded(ElementType.QUICK_WIZARD.protection) {
            mainViewModel.executeQuickWizard(action.guid)
        }

        is QuickLaunchAction.AutomationAction  -> mainViewModel.requestAutomationConfirmation(action.automationId)

        is QuickLaunchAction.TempTargetPreset  -> guarded(ElementType.TEMP_TARGET_MANAGEMENT.protection) {
            mainViewModel.requestTempTargetPresetConfirmation(action.presetId)
        }

        is QuickLaunchAction.ProfileAction     -> guarded(ProtectionCheck.Protection.BOLUS) {
            mainViewModel.requestProfileConfirmation(action.profileName, action.percentage, action.durationMinutes)
        }

        // Not guarded here: the view model asks for protection itself, so the overview scenes sheet gets the
        // same check. Guarding here too would ask twice when the protection timeout is 0.
        is QuickLaunchAction.SceneAction       -> mainViewModel.requestSceneConfirmation(action.sceneId)

        is QuickLaunchAction.PluginAction      -> {
            val index = activePlugin.getPluginsList().indexOfFirst { it::class.simpleName == action.className }
            if (index >= 0) navController.navigate(AppRoute.PluginContent.createRoute(index))
        }
    }
}

/**
 * Tapping a notification that offers to fix what it is complaining about.
 *
 * Only the three that have somewhere to go do anything; the rest are informational, and sending
 * the user to an unrelated screen would be worse than doing nothing.
 */
fun ElementNavigator.handleNotificationAction(notificationId: NotificationId) {
    when (notificationId) {
        NotificationId.IDENTIFICATION_NOT_SET  ->
            navController.navigate(AppRoute.PreferenceScreen.createRoute("data_choice_setting", StringKey.MaintenanceIdentification.key))

        NotificationId.MASTER_PASSWORD_NOT_SET ->
            navController.navigate(AppRoute.PreferenceScreen.createRoute("protection", StringKey.ProtectionMasterPassword.key))

        // Both go to the same picker: one has never chosen a directory, the other chose one and lost
        // the permission behind it. Re-granting is the same action in each case.
        NotificationId.AAPS_DIR_NOT_SELECTED,
        NotificationId.AAPS_DIR_ACCESS_LOST    -> onRequestDirectoryAccess()

        else                                   -> Unit
    }
}

/**
 * A search result.
 *
 * The search stays active on purpose, so back returns to the results rather than to the overview.
 */
fun ElementNavigator.handleSearchResultClick(entry: SearchIndexEntry) {
    when (val item = entry.item) {
        is SearchableItem.Category   -> guarded(ProtectionCheck.Protection.PREFERENCES) {
            navController.navigate(AppRoute.PreferenceScreen.createRoute(item.screenDef.key))
        }

        is SearchableItem.Preference -> guarded(ProtectionCheck.Protection.PREFERENCES) {
            val screenKey = item.parentScreenKey
            if (screenKey != null) navController.navigate(AppRoute.PreferenceScreen.createRoute(screenKey, item.preferenceKey.key))
            else navController.navigate(AppRoute.Preferences.route)
        }

        is SearchableItem.Dialog     -> handleNavigationRequest(NavigationRequest.Element(item.elementType))
        is SearchableItem.Plugin     -> openPlugin(item.pluginRef, navController, activePlugin)
        is SearchableItem.Wiki       -> onOpenUrl(item.url)
    }
}
