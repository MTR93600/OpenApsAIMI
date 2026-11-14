package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.InterfacesStrings
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
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.dashctl.keys.DashBeepKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The Omnipod Dash pump plugin on iOS, the counterpart of Android `OmnipodDashPumpPlugin` and the
 * shape of `IosMedtrumPlugin`. It makes Omnipod Dash a pump the iPhone can choose in the config
 * builder. All of [Pump] goes to [IosDashDriverHost.pump] (the driver plus the answers Android gives
 * from its plugin), so this file holds the plugin lifecycle and nothing about therapy.
 *
 * ## What works and what does not yet (phase 4 step P1)
 *
 * The queue can drive a running pod: bolus, temp basal, basal program, status, and every outcome is
 * written to `PumpSync` ([DashPumpSyncRecorder]). What is missing is how a pod gets running on an
 * iPhone: the activation and deactivation screens come with phase 5 (P3), and the queue commands of
 * Android (silence alerts, resume, deactivate, alert settings, test beep) with P2. Until then
 * [isInitialized] is false (no running pod), so the loop does not run on this pump, and the status
 * checker writes the zero temp basal of Android (`IosDashDriverHost.checkNoActivePod`), so no basal
 * is counted while no pod gives it. It is [OmnipodDash], as Android, so the shared setup wizard lets
 * the user pass its pump screen with no pod yet.
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
) : PumpPluginBase(
    pluginDescription = omnipodDashIosPluginDescription(),
    ownPreferences = DashBeepKey.entries,
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    commandQueue = commandQueue,
    notificationManager = notificationManager
), Pump by host.pump, OmnipodDash {

    /** The status checker of Android, while the plugin runs. */
    private var statusScope: CoroutineScope? = null

    /**
     * Opens the radio once the plugin really runs, as `IosMedtrumPlugin`, and starts the status
     * checker as Android `onStart`: every minute, first after one minute.
     */
    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "IosOmnipodDashPlugin onStart()")
        host.openRadio()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        statusScope = scope
        scope.launch {
            while (isActive) {
                delay(STATUS_CHECK_INTERVAL_MS)
                aapsLogger.info(LTag.PUMP, "statusChecker")
                host.checkNoActivePod()
            }
        }
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "IosOmnipodDashPlugin onStop()")
        statusScope?.cancel()
        statusScope = null
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
 * What the plugin tells the rest of the app about itself: a pump named "Omnipod Dash", and a caution
 * in the config builder that says what does not work on iPhone yet, as `IosMedtrumPlugin` does. The
 * caution is in `:core:interfaces` until this module has its own string table (phase 5, P3), which
 * must also change its text once a pod can be activated here.
 *
 * A function, so a test can read it without building the plugin and the radio behind it.
 */
fun omnipodDashIosPluginDescription(): PluginDescription = PluginDescription()
    .mainType(PluginType.PUMP)
    .pluginName(TextRef.Literal(OMNIPOD_DASH_IOS_PLUGIN_NAME))
    .description(InterfacesStrings.omnipod_dash_ios_caution)
