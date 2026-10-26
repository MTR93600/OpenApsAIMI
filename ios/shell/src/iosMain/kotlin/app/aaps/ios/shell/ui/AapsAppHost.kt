package app.aaps.ios.shell.ui

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import app.aaps.appshell.AapsAppRoot
import app.aaps.appshell.StartupFailedScreen
import app.aaps.appshell.navigation.AppRoute
import app.aaps.appshell.navigation.ElementNavigator
import app.aaps.appshell.navigation.appNavGraph
import app.aaps.appshell.navigation.handleNotificationAction
import app.aaps.appshell.navigation.handleQuickLaunchAction
import app.aaps.appshell.navigation.handleSearchResultClick
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.dst.DstHelper
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.IosNotificationDelegate
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionResult
import app.aaps.core.interfaces.resources.TextRefValueRegistry
import app.aaps.core.interfaces.rx.events.EventAppInitialized
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.delayUntilWallClock
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringKey
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalMetroViewModelFactory
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.icons.IcAaps
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.implementation.lifecycle.IosProtectionLifecycle
import app.aaps.implementation.logging.AAPSLoggerIos
import app.aaps.implementation.maintenance.KeepAliveChecks
import app.aaps.implementation.notifications.requestIosNotificationPermission
import app.aaps.implementation.protection.KeychainUnavailableException
import app.aaps.ios.shell.IosAppStartup
import app.aaps.ios.shell.PluginStoreRegistry
import app.aaps.ios.shell.di.IosAppGraph
import app.aaps.ios.shell.platform.IosLanguage
import app.aaps.ios.shell.platform.notOnThisPlatform
import app.aaps.ios.shell.prefs.IosStorage
import app.aaps.plugins.aps.openAPSAIMI.background.IosAimiBackgroundTasks
import app.aaps.plugins.aps.openAPSAIMI.dashboard.AimiGlassDashboardSnapshotFeed
import app.aaps.plugins.sync.nsclientV3.ws.NsSocketFactory
import app.aaps.shared.clientbindings.ClientGraphBindings
import app.aaps.shared.clientbindings.ClientViewModelFactory
import app.aaps.shared.impl.sharedPreferences.StoreUnreadableException
import app.aaps.ui.compose.configuration.ConfigurationViewModel
import app.aaps.ui.compose.insulinManagement.InsulinManagementViewModel
import app.aaps.ui.compose.loopSheet.LoopActionViewModel
import app.aaps.ui.compose.main.MainViewModel
import app.aaps.ui.compose.main.OverviewScreen
import app.aaps.ui.compose.maintenance.ImportViewModel
import app.aaps.ui.compose.maintenance.MaintenanceViewModel
import app.aaps.ui.compose.manageSheet.ManageViewModel
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import app.aaps.ui.compose.permissionsSheet.PermissionsSheet
import app.aaps.ui.compose.permissionsSheet.PermissionsViewModel
import app.aaps.ui.compose.profileManagement.viewmodels.ProfileEditorViewModel
import app.aaps.ui.compose.profileManagement.viewmodels.ProfileHelperViewModel
import app.aaps.ui.compose.profileManagement.viewmodels.ProfileManagementViewModel
import app.aaps.ui.compose.quickWizard.viewmodels.QuickWizardManagementViewModel
import app.aaps.ui.compose.runningMode.RunningModeManagementViewModel
import app.aaps.ui.compose.scenesSheet.ScenesViewModel
import app.aaps.ui.compose.siteRotationDialog.viewModels.SiteRotationManagementViewModel
import app.aaps.ui.compose.stats.viewmodels.StatsViewModel
import app.aaps.ui.compose.tempTarget.TempTargetManagementViewModel
import app.aaps.ui.compose.treatments.viewmodels.TreatmentsViewModel
import app.aaps.ui.compose.treatmentsSheet.TreatmentViewModel
import app.aaps.ui.search.SearchViewModel
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSBundle
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIViewController


/** The app, built and started once per process: the graph, and what the composition needs from outside it. */
private class StartedApp(
    val graph: IosAppGraph,
    val viewModelFactory: ClientViewModelFactory,
    val appName: String,
    val appIcon: ImageBitmap?
)

private var startedApp: StartedApp? = null

/**
 * Builds the graph and starts the app - plugins, loop, drivers - once per process.
 *
 * Called from the Swift `App.init`, not only from the view. iOS can start the app in the
 * background for a Bluetooth event, and SwiftUI may build no window for such a launch. When the
 * graph was built only with the first screen, nothing was running then: no sensor, no pump, no
 * loop, until the person opened the app. Calling it again, as the view does, is a no-op.
 *
 * A failure is logged and not thrown: an exception that reaches Swift ends the process. Nothing is
 * kept after a failure, so the view tries again when the person opens the app, and shows
 * [StartupFailedScreen] if it fails again.
 */
fun startAapsApp(nsSocketFactory: NsSocketFactory) {
    runCatching { startedOnce(nsSocketFactory) }
        .onFailure { e -> AAPSLoggerIos().error(LTag.CORE, "The app could not start: ${e.message}", e) }
}

/**
 * Opens the settings store, then builds the graph on top of it and starts what has to run before the
 * first screen. Returns the app that is already started, if there is one.
 *
 * The store first and on its own, so that a store that cannot be opened stops here with its own
 * message, which the failure screen can act on, instead of failing somewhere inside the first screen.
 * Nothing is kept when it throws, so the failure screen can call this again.
 */
private fun startedOnce(nsSocketFactory: NsSocketFactory): StartedApp {
    startedApp?.let { return it }
    val store = IosStorage.open()
    // Built once, outside the composition: a graph rebuilt on each recomposition would hand out new
    // singletons every frame - a new database wrapper, a new preference store.
    val graph = createGraphFactory<IosAppGraph.Factory>().create(CoreObjectsGraph, ClientGraphBindings, nsSocketFactory)
    val viewModelFactory = ClientViewModelFactory(graph)
    val logger = graph.logger

    // Before the composition, not beside it: the first view model built reads the active pump, and
    // an empty plugin list there throws from a coroutine and takes the process with it.
    IosAppStartup(
        aapsLogger = logger,
        registry = PluginStoreRegistry(graph.pluginStore, graph.configBuilder),
        contributedPlugins = graph.contributedPlugins,
        apsPlugins = graph.contributedApsPlugins,
        mergeApsPlugins = graph.config.APS,
        startPeriodicWork = { graph.periodicMaintenance.start(graph.appScope) },
        startMasterServices = {
            graph.runningModeReconciler.start()
            graph.runningModeExpiryScheduler.start()
            graph.profileSwitchScheduler.start()
            graph.automationRuntime.start()
            startKeepAliveChecks(graph.keepAliveChecks, graph.dstHelper, graph.dateUtil, graph.appScope, logger)
        },
        // Blocking, but only reads one stored value - the database is touched once, on the first
        // start after the phone stopped being a client. It has to be finished before the Nightscout
        // plugin starts, or its first upload could still use the old marks.
        beforePluginsStart = { runBlocking { graph.nsRoleChange.applyIfChanged() } },
        afterStart = { recordAppStart(graph) },
        // The wait for the plugins runs here, not on the main thread `startAapsApp` is called on.
        startupScope = graph.appScope,
        // Started on the main thread, as they were before the wait was added.
        servicesContext = Dispatchers.Main
    ).run()

    // The AIMI background jobs (ML training, telemetry retention). The coordinator is the one the
    // loop's engine holds, so a model trained in the background reloads the learner the loop doses
    // with - on the next tick, as on Android. Their handlers were registered earlier by
    // `registerAimiBackgroundTasks`, from the Swift `init`: iOS only allows that before launch ends.
    // `install` catches its own failures: a background job must not take the app down.
    IosAimiBackgroundTasks.install(graph.basalMlTrainingCoordinator, logger)

    // Attach to the notification centre. Registering a category only records it now, so that building
    // the graph does not need an app bundle - see `IosNotificationDelegate.install`. It is done here
    // rather than in `IosAppStartup` because only the real app reaches this function: startup is unit
    // tested, and `currentNotificationCenter()` cannot be called from a test binary.
    // Before the first screen composes, or it renders English and only corrects itself on the next
    // recomposition. Android gets this from `Resources`; here the registry has to be told.
    IosLanguage.apply(graph.preferences)
    logger.debug(LTag.CORE, "Language: ${TextRefValueRegistry.locale ?: "English"}")

    // A tapped notification asks for a screen. Registered before the delegate is installed, so a tap
    // that started the app is not missed in the moment between the two. Android reads the same route
    // from an intent extra in `ComposeMainActivity`; everything below the route string is shared.
    installNotificationDeepLinks()

    IosNotificationDelegate.install()

    // At start-up, not at the first notification: an alarm that fires in the background before the
    // person was ever asked would stay silent.
    requestIosNotificationPermission(logger)

    // A snackbar sent while no screen is up becomes a notification instead of being lost. Shared
    // with Android and desktop; started after the delegate above, because it posts through it.
    graph.snackbarNotificationFallback.start()

    // Sets the alarm of a running "time to eat" timer again, as `MainApp` does on an Android master.
    graph.eatReminder.restore()

    // Same placement and the same reason: this touches UIKit, so it cannot live in the graph or in
    // `IosAppStartup`. Until this ran, Nightscout sync was blocked forever - see `startBatteryWatch`.
    graph.receiverStatusStore.startBatteryWatch()

    // Clears a granted PIN or biometric session when the app is backgrounded, which is what Android
    // gets from `ProcessLifecycleListener.onPause`. Same placement and the same UIKit reason.
    IosProtectionLifecycle(logger, graph.protectionCheck).start()

    // Rebuilds the Nightscout socket when the app comes back after iOS suspended it. Same placement
    // and the same UIKit reason as the two above.
    graph.nsAppLifecycle.start()

    // A change made just before the app went to the background may still wait for its background save,
    // and iOS can end a suspended app without a word. Same placement and the same UIKit reason as above.
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
        usingBlock = { store.flush() }
    )

    // Read once, outside the composition, for the same reason as the graph: decoding the icon on
    // every recomposition would be work for nothing.
    // From the bundle, like the icon and for the same reason: one framework serves both Xcode
    // targets, so each target's own `CFBundleDisplayName` is the only thing that can tell them apart.
    val appName = graph.textResolver.gs(graph.config.appName)
    val appIcon = loadAppIcon()
    if (appIcon == null) logger.error(LTag.CORE, "The app bundle gave no icon; showing the plain AAPS mark")
    logger.debug(LTag.CORE, "Starting the AAPS Compose root on iOS")

    return StartedApp(graph, viewModelFactory, appName, appIcon).also { startedApp = it }
}

/**
 * The real AAPS app, running on iOS - the counterpart of `ComposeMainActivity` in `:app`.
 *
 * [AapsAppRoot] is shared code and owns everything above a screen: the theme, the composition
 * locals, the splash gate and the four hosts (snackbar, dialog, password prompt, client-control
 * modal). This file supplies the two things it cannot: the platform's own graph, and the navigation
 * that fills the space it leaves for `content`. Settings are read and written through the encrypted
 * settings store ([IosStorage]).
 *
 * When the start fails, the window shows [StartupFailedScreen] instead, with "Start fresh" for settings
 * that cannot be read and "Try again" for a Keychain that did not answer.
 *
 * ## What iOS answers differently
 *
 * Most callbacks below do what `ComposeMainActivity` does. The ones that cannot are reported on
 * screen and in the log: `reportNotReady` for work still to do (a document picker), and
 * `reportNotAvailable` for what iOS does not allow (opening an Android CGM app, closing the app).
 */
fun aapsAppViewController(nsSocketFactory: NsSocketFactory): UIViewController {
    val startup = runCatching { startedOnce(nsSocketFactory) }

    // Built out here, once, for two reasons: the request it carries has to survive a recomposition,
    // and `AapsAppRoot` collects pending requests in an effect keyed on the lambda, so handing it a
    // new lambda on every recomposition would restart that collection for nothing.
    val navigationRelay = NavigationRelay()
    val onPendingNavigation: (NavigationRequest) -> Unit = navigationRelay::offer

    return ComposeUIViewController {
        // State, because "Start fresh" and "Try again" on the failure screen start the app a second time
        // in place. iOS gives an app no way to restart itself.
        var current by remember { mutableStateOf(startup) }
        current.fold(
            onSuccess = { AapsApp(it, navigationRelay, onPendingNavigation) },
            onFailure = { error ->
                MaterialTheme {
                    StartupFailedScreen(
                        title = "AAPS - startup failed",
                        explanation = failureExplanation(error),
                        error = error,
                        // A failure of either one lands on this same screen again.
                        onRetry = if (error is KeychainUnavailableException) ({ current = runCatching { startedOnce(nsSocketFactory) } }) else null,
                        onStartFresh = if (error is StoreUnreadableException) ({
                            current = runCatching {
                                IosStorage.startFresh()
                                startedOnce(nsSocketFactory)
                            }
                        }) else null
                    )
                }
            }
        )
    }
}

/** The app itself, once [startedOnce] has built it. */
@Composable
private fun AapsApp(app: StartedApp, navigationRelay: NavigationRelay, onPendingNavigation: (NavigationRequest) -> Unit) {
    val graph = app.graph
    val logger = graph.logger
    val viewModelFactory = app.viewModelFactory
    val appName = app.appName
    val appIcon = app.appIcon
    // Rebuilds everything below when a rebuild is asked for - after an import, or a language
    // change. `key` discards the subtree's state, which is why it is keyed on this and nothing
    // else: a scroll position lost on a language change is fine, on every recomposition is not.
    // The language setting, applied while the app runs. Android answers this by recreating the
    // activity, which reloads the `Context` its `Resources` resolve against; here the registry is
    // repointed and the composition rebuilt - the same outcome without a restart iOS could not
    // perform anyway.
    LaunchedEffect(Unit) {
        graph.preferences.observe(StringKey.GeneralLanguage).drop(1).collect {
            IosLanguage.apply(graph.preferences)
            logger.debug(LTag.CORE, "Language changed to ${TextRefValueRegistry.locale ?: "English"}")
            graph.uiRestart.request()
        }
    }
    // The user's "keep screen on" choice, which iOS honours through the idle timer. Placed
    // beside the language effect because both are app wide settings applied while it runs.
    KeepScreenOnEffect(graph.preferences)

    val restart by graph.uiRestart.signal.collectAsState()
    key(restart) {
    // iOS has no ambient application object, so the factory is provided here rather than found.
    CompositionLocalProvider(LocalMetroViewModelFactory provides viewModelFactory) {
        AapsAppRoot(
            config = graph.config,
            preferences = graph.preferences,
            dateUtil = graph.dateUtil,
            decimalFormatter = graph.decimalFormatter,
            profileUtil = graph.profileUtil,
            passwordHasher = graph.passwordHasher,
            passwordCheck = graph.passwordCheck,
            protectionCheck = graph.protectionCheck,
            exportPasswordDataStore = graph.exportPasswordDataStore,
            visibilityContext = graph.visibilityContext,
            nsClient = graph.nsClient,
            rxBus = graph.rxBus,
            snackbarHostPresence = graph.snackbarHostPresence,
            clientControlActionDispatcher = graph.clientControlActionDispatcher,
            bolusProgressData = graph.bolusProgressData,
            commandQueue = graph.commandQueue,
            pumpCommunicationStatus = graph.pumpCommunicationStatus,
            // The real app icon, so the About dialog and the drawer show what the home screen
            // shows. Icon() would tint a logo to one flat colour, so Image() draws it.
            appIcon = { modifier -> AppIconImage(appIcon, modifier) },
            splashLogo = { modifier -> AppIconImage(appIcon, modifier) },
            onNavControllerReady = {},
            onClose = { logger.error(LTag.CORE, "Initialization failed and iOS cannot close an app") },
            // Where a tapped notification ends up. The relay passes it on to the navigator below
            // as soon as there is one, so it still goes through `handleNavigationRequest` and
            // opening settings still asks for authorization first - a deep link must not be a
            // way around that.
            onPendingNavigation = onPendingNavigation
        ) { navController ->
            // Application protection, the counterpart of the request `ComposeMainActivity`
            // makes from `onResume`. Without this nothing on iOS ever asks, so `ProtectionHost`
            // has no request to render and a configured app-launch password never appears - the
            // setting looked saved and did nothing, which is how it was found.
            //
            // Asked at launch **and every time the app comes back to the foreground**, which is
            // the case that matters: a phone handed to someone else while AAPS is backgrounded.
            // Android does the same from `onResume`. When protection is set to "No protection"
            // this costs nothing - `requestProtection` grants straight away without a dialog, so
            // the gate only appears for someone who asked for it.
            var unlocked by remember { mutableStateOf(false) }
            var asking by remember { mutableStateOf(false) }
            val requestUnlock = remember {
                {
                    // Guarded, as Android guards with `isProtectionCheckActive`: `didBecomeActive`
                    // can fire while a prompt is already up, and a second request would stack a
                    // second dialog on the first.
                    if (!asking) {
                        asking = true
                        // Re-locked before asking, or the app stays readable behind the prompt on
                        // the way back from the background.
                        unlocked = false
                        graph.protectionCheck.requestProtection(ProtectionCheck.Protection.APPLICATION) { result ->
                            asking = false
                            unlocked = result == ProtectionResult.GRANTED
                            if (!unlocked) logger.error(LTag.CORE, "Application protection was not granted: $result")
                        }
                    }
                }
            }

            DisposableEffect(Unit) {
                requestUnlock()
                val observer = NSNotificationCenter.defaultCenter.addObserverForName(
                    name = UIApplicationDidBecomeActiveNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                    usingBlock = { requestUnlock() }
                )
                onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
            }

            // Nothing is drawn until it is granted. Showing the app behind the prompt would make
            // the protection cosmetic - the screen it is meant to hide would be readable through
            // the dialog, and on iOS it would also be in the app-switcher snapshot.
            if (!unlocked) return@AapsAppRoot

            // Everything the shared screens ask a platform for that iOS cannot answer ends up in
            // one of these two. Until now they only wrote a line in the log, so from the user's
            // side the button did nothing at all and there was no way to tell a missing feature
            // from a broken one. Both still log; they now also say so on screen.
            //
            // The two are kept apart because the answers are not the same. `reportNotReady` is
            // work still to do - a document picker - and will go away one at a time, so it logs
            // at error like the rest of the missing pieces. `reportNotAvailable` is what iOS is:
            // there is no Dexcom app to open and an app may not close itself, and no amount of
            // work will change that. Promising "not ready yet" there would be a lie, and logging
            // it at error would fill the log with correct behaviour - which is exactly the split
            // `notOnThisPlatform` already draws.
            val snackbarHostState = LocalSnackbarHostState.current
            val uiScope = rememberCoroutineScope()
            val notReadyMessage = stringResource(CoreUiStrings.not_implemented_yet)
            val notHereMessage = stringResource(CoreUiStrings.not_available_on_platform)
            val reportNotReady: (String) -> Unit = { what ->
                logger.notWiredYet(what)
                uiScope.launch { snackbarHostState.showSnackbar(notReadyMessage) }
            }
            val reportNotAvailable: (String) -> Unit = { what ->
                logger.notOnThisPlatform(what)
                uiScope.launch { snackbarHostState.showSnackbar(notHereMessage) }
            }

            // Resolved here rather than inside the NavHost: `appNavGraph` is a builder, not a
            // composable, so a view model cannot be asked for from within it.
            val insulinManagement = metroViewModel<InsulinManagementViewModel>()
            val profileManagement = metroViewModel<ProfileManagementViewModel>()
            val profileEditor = metroViewModel<ProfileEditorViewModel>()
            val profileHelper = metroViewModel<ProfileHelperViewModel>()
            val tempTargetManagement = metroViewModel<TempTargetManagementViewModel>()
            val quickWizardManagement = metroViewModel<QuickWizardManagementViewModel>()
            val runningModeManagement = metroViewModel<RunningModeManagementViewModel>()
            val import = metroViewModel<ImportViewModel>()
            val configuration = metroViewModel<ConfigurationViewModel>()
            val treatments = metroViewModel<TreatmentsViewModel>()
            val stats = metroViewModel<StatsViewModel>()
            val siteRotationManagement = metroViewModel<SiteRotationManagementViewModel>()
            // Assisted rather than contributed, so it is built from its own factory. `fullWindow`
            // is false here for the same reason as on Android: this is the app's own graph, not
            // the full-screen history one.
            val graphs: GraphViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { graph.graphViewModelFactory.create(graph.overviewDataCache, fullWindow = false) }
                }
            )
            val mainViewModel = metroViewModel<MainViewModel>()

            // One instance for the overview badge and the sheet below, as `ComposeMainActivity`
            // holds one for the whole activity. Asked for inside the overview it would be a
            // second instance, and the badge and the sheet would disagree.
            val permissionsViewModel = metroViewModel<PermissionsViewModel>()
            val refreshPermissions: () -> Unit = remember {
                { graph.iosPluginPermissions.refresh { permissionsViewModel.refresh() } }
            }
            val requestPermission: (PermissionGroup) -> Unit = remember {
                { group -> graph.iosPluginPermissions.request(group, refreshPermissions) }
            }
            // Android refreshes in `onResume`. Here: once now, and each time the app is in front
            // again - the user may have changed a permission in the Settings app meanwhile.
            DisposableEffect(Unit) {
                refreshPermissions()
                val observer = NSNotificationCenter.defaultCenter.addObserverForName(
                    name = UIApplicationDidBecomeActiveNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                    usingBlock = { refreshPermissions() }
                )
                onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
            }
            val permState by permissionsViewModel.uiState.collectAsState()
            val permissionsSnackbarHostState = remember { SnackbarHostState() }
            if (permState.showSheet) {
                PermissionsSheet(
                    items = permState.items,
                    snackbarHostState = permissionsSnackbarHostState,
                    onRequestPermission = requestPermission,
                    onDismiss = { permissionsViewModel.dismissSheet() }
                )
            }

            // The shared navigator: every route a tap can lead to is mapped in :appshell, so the
            // only per-platform parts are the four actions below. iOS can honour one of them.
            val navigator = ElementNavigator(
                navController = navController,
                mainViewModel = mainViewModel,
                activePlugin = graph.activePlugin,
                protectionCheck = graph.protectionCheck,
                configBuilder = graph.configBuilder,
                dexcomBoyda = graph.dexcomBoyda,
                // The CGM apps this opens are Android apps, found by package name.
                onOpenCgmApp = { pkg -> reportNotAvailable("open CGM app $pkg") },
                // Only for a path that reaches the navigator with EXIT without going through
                // `handleOnIos` below. iOS gives an app no way to quit itself.
                onExit = { reportNotAvailable("exit from the menu") },
                // Needs a UIDocumentPicker, which nothing on iOS has yet.
                onRequestDirectoryAccess = { reportNotReady("directory access") },
                onOpenUrl = { url -> graph.urlOpener.open(url) }
            )

            // Gives the relay somewhere to send a tapped notification's request. It can only be
            // done here: the navigator needs the nav controller and the main view model, and it
            // does not exist at all until the app is unlocked above. Until then the relay holds
            // the request rather than dropping it.
            //
            // `rememberUpdatedState` because a new navigator is built on each recomposition,
            // while the attachment should outlive them all.
            // Every request goes through here rather than straight to the navigator, for one
            // element: EXIT. The navigator answers it with `configBuilder.exitApp`, which sends
            // `EventAppExit` - and that stops Nightscout sync and other services - before asking
            // the platform to end the process. iOS may not end it, so the app would keep running
            // with sync stopped and nothing on screen saying so. The drawer already hides Exit on
            // iOS (`MainViewModel.showExit`); this makes sure no other path reaches it.
            val handleOnIos: (NavigationRequest) -> Unit = { request ->
                if (request is NavigationRequest.Element && request.type == ElementType.EXIT) reportNotAvailable("exit")
                else navigator.handleNavigationRequest(request)
            }

            val latestHandler by rememberUpdatedState(handleOnIos)
            DisposableEffect(Unit) {
                navigationRelay.attach { request -> latestHandler(request) }
                onDispose { navigationRelay.attach(null) }
            }

            val chips: ChipsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { graph.chipsViewModelFactory.create(graph.overviewDataCache) }
                }
            )

            // The overview is the start destination, the same as Android and desktop. Settings
            // used to be, which left its back arrow inert - there was nothing behind it - and
            // left every other screen unreachable, since they are all reached from the overview.
            // First run opens the setup wizard, as `ComposeMainActivity` does on Android. The
            // route and the screen are already shared through `appNavGraph`; only the trigger
            // was Android's, so a new iOS user landed on an empty overview with nothing to
            // follow and no hint that a wizard existed. Reported from TestFlight.
            LaunchedEffect(Unit) {
                if (!graph.preferences.get(BooleanNonKey.GeneralSetupWizardProcessed)) {
                    graph.protectionCheck.requestProtection(ProtectionCheck.Protection.PREFERENCES) { result ->
                        if (result == ProtectionResult.GRANTED) navController.navigate(AppRoute.SetupWizard.route)
                    }
                }
            }

            NavHost(navController = navController, startDestination = AppRoute.Main.route) {
                appNavGraph(
                    navController = navController,
                    insulinManagementViewModel = insulinManagement,
                    profileManagementViewModel = profileManagement,
                    profileEditorViewModel = profileEditor,
                    profileHelperViewModel = profileHelper,
                    tempTargetManagementViewModel = tempTargetManagement,
                    quickWizardManagementViewModel = quickWizardManagement,
                    runningModeManagementViewModel = runningModeManagement,
                    importViewModel = import,
                    configurationViewModel = configuration,
                    treatmentsViewModel = treatments,
                    statsViewModel = stats,
                    siteRotationManagementViewModel = siteRotationManagement,
                    graphViewModel = graphs,
                    chipsViewModel = chips,
                    swDefinition = graph.swDefinition,
                    rxBus = graph.rxBus,
                    activePlugin = graph.activePlugin,
                    pluginPermissions = graph.pluginPermissions,
                    // Passed so the rules can be read and edited here. `IosAppStartup` starts the
                    // runtime, as `MainApp` does on an Android master.
                    automationRuntime = graph.automationRuntime,
                    preferences = graph.preferences,
                    rh = graph.textResolver,
                    builtInSearchables = graph.builtInSearchables,
                    configBuilder = graph.configBuilder,
                    prefFileList = graph.prefsFileInfo,
                    persistenceLayer = graph.persistenceLayer,
                    visibilityContext = graph.visibilityContext,
                    onNavigationRequest = { request, _ -> handleOnIos(request) },
                    // The same alarm as `ComposeMainActivity`: a dose that did not reach the pump
                    // has to be heard, not only written to the log.
                    onShowDeliveryError = { comment, title ->
                        graph.uiInteraction.runAlarm(comment, graph.textResolver.gs(title), AlarmSound.BOLUS_ERROR)
                    },
                    withProtection = guardedBy(graph.protectionCheck),
                    // The same check `ComposeMainActivity` makes. It used to grant unconditionally
                    // with a "not wired yet" line, which is a stub that fails open on a protection
                    // check: with settings protection configured, the edit pencil on the
                    // QuickWizard, profile, insulin and temp-target screens opened with no prompt.
                    // Those values feed dosing.
                    // `protectionCheck` was already on the graph - `withProtection` above uses it.
                    requestEditModeAuthorization = { onGranted ->
                        graph.protectionCheck.requestAuthorization(ProtectionCheck.Protection.PREFERENCES) { result ->
                            if (result.grantedLevel != null) onGranted()
                        }
                    },
                    onRefreshPermissions = refreshPermissions,
                    // The same call as `ComposeMainActivity`.
                    onExecuteQuickWizard = { guid -> mainViewModel.executeQuickWizard(guid) },
                    onRequestDirectoryAccess = { reportNotReady("directory access - iOS needs a document picker") },
                    onRequestPermission = requestPermission,
                    overview = {
                        OverviewScreen(
                            mainViewModel = mainViewModel,
                            manageViewModel = metroViewModel<ManageViewModel>(),
                            maintenanceViewModel = metroViewModel<MaintenanceViewModel>(),
                            statusViewModel = metroViewModel<StatusViewModel>(),
                            treatmentViewModel = metroViewModel<TreatmentViewModel>(),
                            scenesViewModel = metroViewModel<ScenesViewModel>(),
                            loopActionViewModel = metroViewModel<LoopActionViewModel>(),
                            searchViewModel = metroViewModel<SearchViewModel>(),
                            permissionsViewModel = permissionsViewModel,
                            graphViewModel = graphs,
                            chipsViewModel = chips,
                            aimiGlassDashboardData = remember { AimiGlassDashboardSnapshotFeed.observe() },
                            dateUtil = graph.dateUtil,
                            activePlugin = graph.activePlugin,
                            config = graph.config,
                            objectives = graph.objectives,
                            bgQualityCheck = graph.bgQualityCheck,
                            notificationManager = graph.notificationManager,
                            uiInteraction = graph.uiInteraction,
                            builtInSearchables = graph.builtInSearchables,
                            bolusProgressData = graph.bolusProgressData,
                            clientControlActionDispatcher = graph.clientControlActionDispatcher,
                            commandQueue = graph.commandQueue,
                            pumpCommunicationStatus = graph.pumpCommunicationStatus,
                            appName = appName,
                            authorizationFailedMessage = "Authorization failed",
                            onNavigate = handleOnIos,
                            onSearchResultClick = { entry -> navigator.handleSearchResultClick(entry) },
                            onNotificationActionClick = { n -> navigator.handleNotificationAction(n.id) },
                            onQuickLaunchActionClick = { action -> navigator.handleQuickLaunchAction(action) },
                            // The destination is already in the shared graph and the view model is
                            // already handed to it above, so this is the same call the other two
                            // shells make. It was a placeholder only while iOS had no importer.
                            onImportSettingsNavigate = { source -> navController.navigate(AppRoute.ImportSettings.createRoute(source.name)) },
                            // Needs a UIDocumentPicker, which nothing on iOS has yet.
                            onDirectoryClick = { reportNotReady("directory picker") },
                            // The Google sign in, and the only thing that reaches this callback.
                            // Shown *over* AAPS rather than handed to Safari: switching to Safari
                            // lets iOS suspend this app within seconds, and the loopback listener
                            // waiting for Google's redirect goes with it - so the sign in would
                            // never complete. `urlOpener` is right for ordinary links and wrong
                            // for this one. See AuthBrowser.
                            onLaunchBrowser = { url -> graph.authBrowser.show(url) },
                            // Android says "bring the app back" because the browser took over the
                            // screen; on iOS the browser is a sheet this app presented, so the
                            // same intent is to close it.
                            //
                            // Only ever after the wait has ended, never while it is running. This
                            // event is also emitted when a sign in fails, and the first version
                            // dismissed on that too - which closed the Google page while the user
                            // was still typing into it, because the wait had timed out at a
                            // minute. The timeout is now five minutes; this stays defensive
                            // because "bring the app forward" is harmless on Android and
                            // destructive here.
                            onBringToForeground = { graph.authBrowser.dismiss() },
                            // No activity to recreate. A Compose scene is not restarted this way.
                            onRecreateActivity = { logger.debug(LTag.CORE, "Nothing to recreate on iOS after a database reset") },
                            onAuthorizationFailed = { logger.error(LTag.CORE, "Authorization failed") },
                            autoShowNotificationSheet = false,
                            onAutoShowConsumed = {}
                        )
                    }
                )
            }
        }
    }
    }
}

/**
 * Registers the AIMI background task handlers (ML training and telemetry retention) and nothing else.
 *
 * Called from the Swift `ClientApp.init`, never from [aapsAppViewController]. iOS requires
 * `BGTaskScheduler` handlers to be registered before the app finishes launching, and the view
 * controller is made later than that: registering there can crash the app. For the same reason this
 * needs no graph - none exists yet. The part that needs the graph is
 * `IosAimiBackgroundTasks.install`, which `startAapsApp` calls once the graph is built.
 * Keep the two apart.
 */
fun registerAimiBackgroundTasks() {
    IosAimiBackgroundTasks.register()
}

/**
 * What `MainApp.doInit` records once the app has started: a version change, the "AAPS started" note
 * and `EventAppInitialized`, which makes the IOB/COB calculator start its first calculation.
 *
 * Android waits 10 s before the two records, so that its resources are ready for translations.
 * Nothing here needs that: the text registry is set up before the plugins start.
 */
private suspend fun recordAppStart(graph: IosAppGraph) {
    // The build number is the counterpart of Android's version code. iOS has no git data in the build.
    val buildNumber = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
    graph.persistenceLayer.insertVersionChangeIfChanged(graph.config.VERSION_NAME, buildNumber, null, null)
    if (graph.preferences.get(BooleanKey.NsClientLogAppStart))
        graph.persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = graph.dateUtil.now(),
                type = TE.Type.NOTE,
                // The same stored text as Android, which builds it the same way. It is a record
                // that is synced to Nightscout, not text on a screen.
                note = graph.textResolver.gs(CoreUiStrings.androidaps_start) + " - " + graph.config.deviceManufacturer + " " + graph.config.deviceModelForUpload,
                glucoseUnit = GlucoseUnit.MGDL
            ),
            action = Action.START_AAPS,
            source = Sources.Aaps,
            note = "",
            listValues = listOf()
        )
    graph.rxBus.send(EventAppInitialized())
}

/**
 * Runs the master pump and APS checks every five minutes, as the Android `KeepAliveWorker` does.
 *
 * Waits on the wall clock: while iOS suspends the app a plain delay stops counting, so a check that
 * was due during the suspension runs at the first wake-up - a sensor reading, a pump event -
 * instead of five minutes after it. The first pass waits too, as Android's first worker run does.
 */
private fun startKeepAliveChecks(checks: KeepAliveChecks, dstHelper: DstHelper, dateUtil: DateUtil, scope: CoroutineScope, logger: AAPSLogger) {
    scope.launch {
        while (isActive) {
            delayUntilWallClock(dateUtil.now() + KEEP_ALIVE_INTERVAL_MS, dateUtil::now)
            try {
                // Before the checks, in the same order as `KeepAliveWorker`: a clock change found
                // here suspends the loop before the checks look at it.
                dstHelper.dstCheck()
                checks.run()
            } catch (e: Exception) {
                logger.error(LTag.CORE, "Keep-alive checks failed", e)
            }
        }
    }
}

private const val KEEP_ALIVE_INTERVAL_MS = 5L * 60L * 1000L

/** One line, one shape, so a run makes it obvious which platform callbacks are still placeholders. */
private fun AAPSLogger.notWiredYet(what: String) = error(LTag.CORE, "Not wired up on iOS yet: $what")

/**
 * The app icon, or the plain AAPS mark when the bundle would not give one up.
 *
 * A fallback rather than nothing on screen: the About dialog and the drawer both have a place for
 * it, and an empty space there reads as a broken screen.
 */
@Composable
private fun AppIconImage(icon: ImageBitmap?, modifier: Modifier) {
    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = modifier)
    else Icon(imageVector = IcAaps, contentDescription = null, modifier = modifier)
}

/**
 * Runs an action only when the user clears the protection it sits behind.
 *
 * The same shape as desktop's `Main.kt` and Android's `navigator.guarded`. Named rather than written
 * inline at the call site so it can be tested: this used to be `{ _, action -> action() }`, left over
 * from when iOS had no `ProtectionCheck` of its own, and it ran **every** protected action without
 * asking - bolus entry and settings edits included. That is invisible on a device with protection
 * turned off, which is most of them, so it is worth a test rather than a look.
 *
 * Anything other than [ProtectionResult.GRANTED] - denied, or cancelled by backing out of the prompt
 * - must not run the action.
 */
internal fun guardedBy(protectionCheck: ProtectionCheck): (ProtectionCheck.Protection, () -> Unit) -> Unit =
    { protection, action ->
        protectionCheck.requestProtection(protection) { result ->
            if (result == ProtectionResult.GRANTED) action()
        }
    }

/**
 * Words a user can act on, for the two failures of the settings store. Both are normal events rather
 * than bugs. Any other failure gets none: its message is the useful part, and the screen shows it.
 */
private fun failureExplanation(error: Throwable): List<String> = when (error) {
    is StoreUnreadableException     -> listOf(
        "Your settings on this phone cannot be read. They are encrypted with a key kept in the Keychain of this " +
            "phone, and that key does not open them. This happens when the Keychain was reset, or when the file " +
            "is damaged.",
        "Start fresh moves the old file aside (it is kept) and starts with empty settings. " +
            "You then need to set up AAPS on this phone again before it can loop."
    )

    is KeychainUnavailableException -> listOf(
        "The Keychain did not answer. AAPS keeps the key to your settings there. Unlock the phone and try again."
    )

    else                            -> emptyList()
}
