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
import dev.zacsweers.metro.IntKey as MetroIntKey

/**
 * The Omnipod Dash pump plugin on iOS, the counterpart of Android `OmnipodDashPumpPlugin` and the
 * shape of `IosMedtrumPlugin`. It makes Omnipod Dash a pump the iPhone can choose in the config
 * builder. All of [Pump] goes to [IosDashDriverHost.pump] (the driver plus the answers Android gives
 * from its plugin), so this file holds the plugin lifecycle and nothing about therapy.
 *
 * ## What works and what does not yet (phase 4 step P2)
 *
 * The queue can drive a running pod: bolus, temp basal, basal program, status, and the queue
 * commands of Android (silence alerts, resume, deactivate, alert settings, test beep, disable suspend
 * alerts, time change). `PumpSync` is written as Android writes it ([DashPumpSyncRecorder]), and the
 * notices, the status checker and the checks after each command are Android's ([DashPluginRules]).
 * What is missing is how a pod gets running on an iPhone: the activation and deactivation screens
 * come with phase 5 (P3); they call [IosDashDriverHost.rules] (`activatePodPart1`, `activatePodPart2`,
 * `discardPod`). Until then [isInitialized] is false (no running pod), so the loop does not run on
 * this pump, and the status checker writes the zero temp basal of Android, so no basal is counted
 * while no pod gives it. It is [OmnipodDash], as Android, so the shared setup wizard lets the user
 * pass its pump screen with no pod yet.
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
    ownPreferences = DashBeepKey.entries + DashAlertBooleanKey.entries + DashAlertIntKey.entries + DashSoundKey.entries,
    aapsLogger = aapsLogger,
    rh = rh,
    preferences = preferences,
    commandQueue = commandQueue,
    notificationManager = notificationManager
), Pump by host.pump, OmnipodDash {

    /** The status checker of Android, while the plugin runs. */
    private var statusScope: CoroutineScope? = null

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
