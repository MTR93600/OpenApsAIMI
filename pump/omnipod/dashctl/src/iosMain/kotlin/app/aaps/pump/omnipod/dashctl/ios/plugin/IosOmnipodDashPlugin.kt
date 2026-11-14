package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.plugin.PluginType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.icons.IcPluginOmnipod
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.ui.DashOverviewContent
import app.aaps.pump.omnipod.dashctl.ui.DashPodWizardModel
import app.aaps.pump.omnipod.dashctl.ui.DashPodWizardScreen
import app.aaps.pump.omnipod.dashctl.ui.DashWizardAppHost
import app.aaps.pump.omnipod.dashctl.ui.DashWizardRequest
import app.aaps.pump.omnipod.dashctl.ui.dashSettingsScreen
import app.aaps.core.keys.interfaces.PreferenceItem
import kotlin.time.Clock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.OmnipodDash
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.dashctl.keys.DashAlertBooleanKey
import app.aaps.pump.omnipod.dashctl.keys.DashAlertIntKey
import app.aaps.pump.omnipod.dashctl.keys.DashBeepKey
import app.aaps.pump.omnipod.dashctl.keys.DashSoundKey
import app.aaps.pump.omnipod.dashctl.queue.command.CommandUpdateAlertConfiguration
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationProtectedDataDidBecomeAvailable
import platform.darwin.NSObjectProtocol
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The Omnipod Dash pump plugin on iOS, the counterpart of Android `OmnipodDashPumpPlugin` and the
 * shape of `IosMedtrumPlugin`. It makes Omnipod Dash a pump the iPhone can choose in the config
 * builder. All of [Pump] goes to [IosDashDriverHost.pump] (the driver plus the answers Android gives
 * from its plugin), so this file holds the plugin lifecycle and nothing about therapy.
 *
 * ## What it offers (phase 5, P3)
 *
 * The queue drives a running pod: bolus, temp basal, basal program, status, and the queue commands of
 * Android (silence alerts, resume, deactivate, alert settings, test beep, disable suspend alerts, time
 * change). `PumpSync` is written as Android writes it ([DashPumpSyncRecorder]), and the notices, the
 * status checker and the checks after each command are Android's ([DashPluginRules]).
 *
 * The pump screen ([DashOverviewContent], built when it is opened) shows the pod and has the buttons
 * of Android Dash; "Activate Pod" and "Deactivate Pod" open the wizard ([DashPodWizardScreen] over
 * [DashPodWizardModel]), which calls the pod through [IosDashDriverHost.wizardPod]. With no running
 * pod [isInitialized] is false, so the loop does not run on this pump, and the status checker writes
 * the zero temp basal of Android, so no basal is counted while no pod gives it. It is [OmnipodDash],
 * as Android, so the shared setup wizard lets the user pass its pump screen with no pod yet.
 *
 * ## Keys and buckets
 *
 * `@MetroIntKey(1080)` is the number of the Android `OmnipodDashPumpPlugin`, so both platforms order
 * Dash in the same place. They never meet: this file is in iosMain of `:pump:omnipod:dashctl`, which
 * the Android app does not use. No `@PumpDriver` qualifier, as `IosMedtrumPlugin`: `IosAppGraph`
 * puts the iOS pump drivers in the unqualified `Map<Int, PluginBase>` bucket.
 *
 * The iPhone is a full master phone: this plugin drives the pod itself, never through AAPSClient.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(1080)
@SingleIn(AppScope::class)
@Inject
class IosOmnipodDashPlugin(
    aapsLogger: AAPSLogger,
    override val rh: TextResolver,
    preferences: Preferences,
    commandQueue: CommandQueue,
    notificationManager: NotificationManager,
    private val host: IosDashDriverHost,
    // Providers: only the pump screen and the wizard need these, and only when they are opened.
    private val profileFunction: () -> ProfileFunction,
    private val profileRepository: () -> ProfileRepository,
    private val insulinManager: () -> InsulinManager,
    private val persistenceLayer: () -> PersistenceLayer,
    private val pumpSync: () -> PumpSync,
    private val rxBus: () -> RxBus,
    private val dateUtil: () -> DateUtil,
    private val concentrationHelper: () -> ConcentrationHelper,
) : PumpPluginBase(
    // The pump screen. Built when it is opened, so building the plugin builds no UI.
    pluginDescription = omnipodDashIosPluginDescription().composeContent { plugin -> (plugin as IosOmnipodDashPlugin).overviewContent() },
    ownPreferences = DashBeepKey.entries + DashAlertBooleanKey.entries + DashAlertIntKey.entries + DashSoundKey.entries,
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    commandQueue = commandQueue,
    notificationManager = notificationManager
), Pump by host.pump, OmnipodDash {

    /**
     * The settings screen of Android Dash: beeps, pod alerts and notification sounds, with the same
     * keys and defaults ([dashSettingsScreen]). A changed alert setting goes to the pod through the
     * queue ([onStart]); the beeps and sounds are read again for each command and notice.
     */
    override fun getPreferenceScreenContent(): PreferenceItem =
        dashSettingsScreen(title = TextRef.Literal(OMNIPOD_DASH_IOS_PLUGIN_NAME), icon = pluginDescription.icon)

    /** The pump screen: the pod, its buttons, and the wizards. */
    private fun overviewContent() = DashOverviewContent(
        stateManager = host.stateManager,
        commandQueue = commandQueue,
        rxBus = rxBus(),
        rh = rh,
        format = IosDashOverviewFormat(dateUtil(), concentrationHelper(), rh),
        caution = DashStrings.omnipod_dash_ios_caution,
        title = OMNIPOD_DASH_IOS_PLUGIN_NAME,
        now = { Clock.System.now().toEpochMilliseconds() },
        wizard = { request, setToolbarConfig, onClose -> PodWizard(request, setToolbarConfig, onClose) },
        discardPod = { host.discardPodFromScreen() },
        history = { host.history.recentRecords() }
    )

    /**
     * One wizard per opened screen. Its work runs on [IosDashDriverHost.wizardScope], not on the scope
     * of the screen, so leaving the screen cannot stop a part in the middle or skip the records after
     * it. The toolbar is the wizard's while it shows (no back arrow), and the iPhone does not lock
     * during the wizard ([DashKeepScreenOn]), as Android `KeepScreenOnEffect`.
     */
    @Composable
    private fun PodWizard(request: DashWizardRequest, setToolbarConfig: (ToolbarConfig) -> Unit, onClose: () -> Unit) {
        DashKeepScreenOn()
        val model = remember(request) {
            DashPodWizardModel(
                pod = host.wizardPod,
                host = DashWizardAppHost(
                    profileFunction = profileFunction(),
                    profileRepository = profileRepository(),
                    insulinManager = insulinManager(),
                    persistenceLayer = persistenceLayer(),
                    pumpSync = pumpSync(),
                    preferences = preferences,
                    rh = rh,
                    rxBus = rxBus(),
                    dateUtil = dateUtil()
                ),
                rh = rh,
                aapsLogger = aapsLogger,
                scope = host.wizardScope
            )
        }
        LaunchedEffect(model) {
            model.prepare()
            when (request) {
                is DashWizardRequest.Activation -> model.startActivation(request.type)
                DashWizardRequest.Deactivation  -> model.startDeactivation()
            }
        }
        DashPodWizardScreen(model = model, onClose = onClose, setToolbarConfig = setToolbarConfig)
    }

    /** The status checker of Android, while the plugin runs. */
    private var statusScope: CoroutineScope? = null

    /** See [onStart]: iOS tells when the Keychain can be read again after a restart. */
    private var protectedDataObserver: NSObjectProtocol? = null

    /**
     * Opens the radio once the plugin really runs, as `IosMedtrumPlugin`, and as Android `onStart`:
     * the status checker every minute, first after one minute ([DashPluginRules.statusCheck]), and a
     * changed alert setting sends the alerts to the pod through the queue
     * ([CommandUpdateAlertConfiguration]).
     */
    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "IosOmnipodDashPlugin onStart()")
        host.openRadio()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        statusScope = scope
        // When iOS started the app before the first unlock, the Keychain could not be read and every
        // pod command is refused. Read it again as soon as iOS says it can be read (the status checker
        // and every command also try again).
        protectedDataObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationProtectedDataDidBecomeAvailable, `object` = null, queue = null
        ) { _ -> scope.launch { host.rules.podStateKnown() } }
        scope.launch {
            while (isActive) {
                delay(STATUS_CHECK_INTERVAL_MS)
                try {
                    host.rules.statusCheck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The next run tries again, as the Android handler posts the next run whatever happened.
                    aapsLogger.error(LTag.PUMP, "Dash status checker failed", e)
                }
            }
        }
        merge(
            preferences.observe(DashAlertBooleanKey.ExpirationReminder).drop(1).map {},
            preferences.observe(DashAlertIntKey.ExpirationReminderHours).drop(1).map {},
            preferences.observe(DashAlertBooleanKey.ExpirationAlarm).drop(1).map {},
            preferences.observe(DashAlertIntKey.ExpirationAlarmHours).drop(1).map {},
            preferences.observe(DashAlertBooleanKey.LowReservoirAlert).drop(1).map {},
            preferences.observe(DashAlertIntKey.LowReservoirAlertUnits).drop(1).map {},
        ).collectResilient(scope, aapsLogger, LTag.PUMP) { commandQueue.customCommand(CommandUpdateAlertConfiguration()) }
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "IosOmnipodDashPlugin onStop()")
        statusScope?.cancel()
        statusScope = null
        protectedDataObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        protectedDataObserver = null
        host.shutdown()
        super.onStop()
    }

    private companion object {

        /** Android `STATUS_CHECK_INTERVAL_MS`. */
        const val STATUS_CHECK_INTERVAL_MS = 60_000L
    }
}

/** The pump brand, so it is not translated (as Android `omnipod_dash_name`, which no locale changes). */
const val OMNIPOD_DASH_IOS_PLUGIN_NAME: String = "Omnipod Dash"

/**
 * What the plugin tells the rest of the app about itself: a pump named "Omnipod Dash", with the icon
 * and the description of Android Dash. The caution that the driver is new on the iPhone is on the
 * pump screen ([DashStrings.omnipod_dash_ios_caution]).
 *
 * A function, so a test can read it without building the plugin and the radio behind it.
 */
fun omnipodDashIosPluginDescription(): PluginDescription = PluginDescription()
    .mainType(PluginType.PUMP)
    .icon(IcPluginOmnipod)
    .pluginName(TextRef.Literal(OMNIPOD_DASH_IOS_PLUGIN_NAME))
    .description(DashStrings.omnipod_dash_pump_description)
