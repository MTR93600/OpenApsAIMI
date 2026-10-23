package app.aaps.pump.medtrum.ios

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.medtrum.MedtrumStrings
import app.aaps.pump.medtrum.compose.MedtrumPumpPickerScreen
import app.aaps.pump.medtrum.util.MedtrumDeviceName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.medtrum.compose.patch.MedtrumPatchWizardModel
import app.aaps.pump.medtrum.compose.patch.MedtrumPatchWizardScreen
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import app.aaps.core.interfaces.pump.Medtrum
import app.aaps.core.interfaces.pump.PumpEnactResult
import kotlinx.coroutines.runBlocking
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The plugin wrapper around the iOS Medtrum driver.
 *
 * The driver itself ([IosMedtrumPump]) has existed for a while and is covered by tests, but nothing
 * registered it as a plugin, so `ActivePlugin.activePump` on iOS could only ever be the virtual
 * pump. This class is that registration. All of [Pump] is delegated to the driver through
 * [IosMedtrumDriverHost], so this file holds plugin lifecycle and nothing about therapy.
 *
 ## It can be switched on, and says what is different here
 *
 * The plugin used to declare [EnforcedState.Disabled] because nothing could tell it which pump was
 * yours. That is no longer true: [MedtrumIosIntentKey.PickPump] opens a Bluetooth scan that reads
 * the serial out of the advertisement and keeps it, so `MedtrumSessionState.pumpSN` holds a real
 * value and the transport can match that pump. With a serial the driver connects to a patch that
 * is already running and the loop can drive it.
 *
 * Changing the patch - fill, prime, activate, and taking it off - is [MedtrumIosIntentKey.ChangePatch]:
 * the shared `MedtrumPatchWizardScreen` over the headless `MedtrumPatchSession` of the host.
 *
 * ## Keys and buckets
 *
 * `@MetroIntKey(1120)` is the same number the Android `MedtrumPlugin` uses, so the two platforms
 * order Medtrum in the same place. They never meet: this file is in `iosMain` and the Android one
 * is in `pump/medtrum/src/main`.
 *
 * No `@PumpDriver` qualifier, unlike Android: that qualifier is an Android-only annotation, and
 * `IosAppGraph` puts the iOS pump drivers in the unqualified `Map<Int, PluginBase>` bucket.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@MetroIntKey(1120)
@SingleIn(AppScope::class)
@Inject
class IosMedtrumPlugin(
    aapsLogger: AAPSLogger,
    override val rh: TextResolver,
    preferences: Preferences,
    commandQueue: CommandQueue,
    notificationManager: NotificationManager,
    private val host: IosMedtrumDriverHost,
    // Providers: only the patch wizard needs these, and only when it is opened.
    private val profileFunction: () -> ProfileFunction,
    private val profileRepository: () -> ProfileRepository,
    private val insulinManager: () -> InsulinManager,
    private val pumpSync: () -> PumpSync,
    private val persistenceLayer: () -> PersistenceLayer,
    private val rxBus: () -> RxBus,
    private val dateUtil: () -> DateUtil
) : PumpPluginBase(
    // The pump screen of the overview. Built when it is opened, so building the plugin builds no UI.
    pluginDescription = medtrumIosPluginDescription().composeContent { plugin -> (plugin as IosMedtrumPlugin).overviewContent() },
    ownPreferences = MedtrumIosIntentKey.entries,
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    commandQueue = commandQueue,
    notificationManager = notificationManager
), Pump by host.driver, Medtrum {

    // The queue's Medtrum commands (clear alarms, update time, load events, user settings). They are
    // blocking calls in the shared interface and run on the queue's own thread, never on the main one.
    override fun loadEvents(): PumpEnactResult = runBlocking { host.driver.loadEventsCommand() }
    override fun setUserOptions(): PumpEnactResult = runBlocking { host.driver.setUserOptions() }
    override fun clearAlarms(): PumpEnactResult = runBlocking { host.driver.clearAlarms() }
    override fun updateTime(): PumpEnactResult = runBlocking { host.driver.updateTime() }

    /** The patch is taken off with the patch wizard, which asks the person to confirm. */
    override fun deactivate(): PumpEnactResult = host.driver.refuse("Deactivate the patch with Change Patch")


    /**
     * The settings screen of this plugin: one entry, the pump chooser.
     *
     * It opens through [withCompose] rather than an Activity, which is how every shared screen
     * reaches iOS. The screen is shared code - Android could show the very same one - and the only
     * iPhone part is the discovery session behind it.
     */
    override fun getPreferenceScreenContent(): PreferenceItem = PreferenceSubScreenDef(
        key = "medtrum_ios_settings",
        title = TextRef.Literal(MEDTRUM_IOS_PLUGIN_NAME),
        summary = MedtrumStrings.medtrum_pick_pump_summary,
        items = listOf(
            MedtrumIosIntentKey.PickPump.withCompose(
                ComposeScreenContent { onBack ->
                    val pumps by host.discovery.pumps.collectAsState()
                    MedtrumPumpPickerScreen(
                        devices = pumps.map { ScannedDevice(name = it.name, address = it.id) },
                        chosenSerialHex = host.store.loadPumpSerial()?.let { MedtrumDeviceName.serialHex(it) },
                        bluetoothOff = !host.discovery.scanning && pumps.isEmpty(),
                        onStartScan = {
                            // The scan needs the radio, and opening it is what makes iOS ask for
                            // the Bluetooth permission. It happens here rather than in the body of
                            // the screen because the body runs again on every recomposition, while
                            // this runs once when the screen appears. Calling it again is safe
                            // anyway: the central keeps the one manager it already made.
                            host.openRadio()
                            host.discovery.start()
                        },
                        onStopScan = { host.discovery.stop() },
                        onSelect = { device ->
                            pumps.firstOrNull { it.id == device.address }?.let { host.discovery.select(it) }
                        },
                        onBack = onBack
                    )
                }
            ),
            MedtrumIosIntentKey.ChangePatch.withCompose(
                ComposeScreenContent { onBack -> PatchWizard(onClose = onBack) }
            )
        ),
        icon = pluginDescription.icon
    )

    /** The pump screen: state, refresh, clear alarms, change patch. */
    private fun overviewContent() = MedtrumIosOverviewContent(
        host = host,
        commandQueue = commandQueue,
        rh = rh,
        dateUtil = dateUtil(),
        patchWizard = { onClose -> PatchWizard(onClose) }
    )

    /** The "Change Patch" wizard, opened from the settings entry and from the pump screen. */
    @Composable
    private fun PatchWizard(onClose: () -> Unit) {
        val scope = rememberCoroutineScope()
        val model = remember { patchWizardModel(scope).also { it.open() } }
        MedtrumPatchWizardScreen(
            model = model,
            scanContent = { onCancel ->
                val pumps by host.discovery.pumps.collectAsState()
                MedtrumPumpPickerScreen(
                    devices = pumps.map { ScannedDevice(name = it.name, address = it.id) },
                    chosenSerialHex = host.store.loadPumpSerial()?.let { MedtrumDeviceName.serialHex(it) },
                    bluetoothOff = !host.discovery.scanning && pumps.isEmpty(),
                    onStartScan = {
                        host.openRadio()
                        host.discovery.start()
                    },
                    onStopScan = { host.discovery.stop() },
                    onSelect = { device ->
                        val chosen = pumps.firstOrNull { it.id == device.address }
                        if (chosen != null && host.discovery.select(chosen)) model.pumpChosen()
                    },
                    onBack = onCancel
                )
            },
            onClose = onClose
        )
    }

    /** One wizard per opened screen, living as long as that screen does. */
    private fun patchWizardModel(scope: CoroutineScope) = MedtrumPatchWizardModel(
        patch = host.patch,
        state = host.state,
        profileFunction = profileFunction(),
        profileRepository = profileRepository(),
        insulinManager = insulinManager(),
        pumpSync = pumpSync(),
        persistenceLayer = persistenceLayer(),
        preferences = preferences,
        rh = rh,
        rxBus = rxBus(),
        dateUtil = dateUtil(),
        aapsLogger = aapsLogger,
        scope = scope
    )

    /**
     * Opens the radio once the plugin is really running.
     *
     * Not reachable while the enforcement above holds - a forced-off plugin is stopped, never
     * started - and kept correct so that lifting the enforcement needs no second change here.
     */
    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "IosMedtrumPlugin onStart()")
        host.openRadio()
        host.startExpiryWarningCheck()
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "IosMedtrumPlugin onStop()")
        host.shutdown()
        super.onStop()
    }
}

/** The pump brand, so it is not translated. */
const val MEDTRUM_IOS_PLUGIN_NAME: String = "Medtrum"

/**
 * What the plugin tells the rest of the app about itself.
 *
 * No `enforce` any more: the switch is the person's. The description is a real translated resource
 * now - the module owns a string table since it became multiplatform - and it carries the one
 * limit that matters, that a patch has to be started elsewhere.
 *
 * A function rather than an expression inside the constructor call so that a test can read the
 * description without building the plugin and the radio behind it.
 */
fun medtrumIosPluginDescription(): PluginDescription = PluginDescription()
    .mainType(PluginType.PUMP)
    .pluginName(TextRef.Literal(MEDTRUM_IOS_PLUGIN_NAME))
    .description(MedtrumStrings.medtrum_ios_caution)
