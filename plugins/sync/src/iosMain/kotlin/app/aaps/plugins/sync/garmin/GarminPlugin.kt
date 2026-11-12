package app.aaps.plugins.sync.garmin

import app.aaps.core.data.model.GV
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.ble.session.IosRadioWake
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withClick
import app.aaps.core.ui.compose.icons.IcPluginGarmin
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.sync.SyncStrings
import app.aaps.plugins.sync.garmin.keys.GarminBooleanKey
import app.aaps.plugins.sync.garmin.keys.GarminIntKey
import app.aaps.plugins.sync.garmin.keys.GarminIntNonKey
import app.aaps.plugins.sync.garmin.keys.GarminLongNonKey
import app.aaps.plugins.sync.garmin.keys.GarminStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import platform.posix.usleep

/**
 * Garmin on the iPhone.
 *
 * The watch still asks over HTTP, and [IosGarminHttpServer] still answers with [GarminRequests], the
 * same rules as Android. That pull only works while this process is running. Between the CGM wakes
 * iOS suspends the app, so the watch's request times out (`-300`). While a wake has the process up,
 * this plugin also pushes the same `glucose` message Android pushes, through the Connect IQ SDK
 * ([ConnectIqCompanion]), on every new glucose value and every loop result.
 *
 * The HTTP server is unchanged and stays the fallback. The push is [GarminBooleanKey.ConnectIqPush],
 * on by default. Android does not read that key.
 *
 * Incoming Connect IQ messages are logged and not acted on. Android's messenger receives them and
 * its callback is empty: heart rate, steps, carbs, bolus and targets come in on the HTTP requests,
 * and those still do here.
 *
 * It has the same class name as the Android plugin on purpose: the "enabled" setting is stored under
 * `ConfigBuilder_Enabled_SYNC_<class name>`, so a settings file imported from an Android phone turns
 * Garmin on here too.
 *
 * ⚠️ ASYNC IMPACT: the collectors run on [scope]. The SDK calls [callback] on its own thread; the
 * work is handed to [scope]. [onRadioWake] runs on the main queue: [IosRadioWake.ping] only enqueues
 * it there, so the CoreBluetooth queue never opens the socket and never calls UIKit.
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(370)
@SingleIn(AppScope::class)
@Inject
class GarminPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer,
    config: Config,
    notificationManager: NotificationManager,
    private val rxBus: RxBus,
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(IcPluginGarmin)
        .pluginName(SyncStrings.garmin)
        .description(SyncStrings.garmin_description),
    ownPreferences = GarminIosIntentKey.entries + GarminIosStringNonKey.entries +
        GarminStringKey.entries + GarminBooleanKey.entries + GarminIntKey.entries +
        GarminIntNonKey.entries + GarminLongNonKey.entries,
    aapsLogger, rh, preferences, notificationManager
) {

    /** The answers to the watch, shared with Android. */
    val requests = GarminRequests(aapsLogger, rh, preferences, loopHub, persistenceLayer, config, notificationManager)

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var server: IosGarminHttpServer? = null
    private val push = ConnectIqPush(nowMs = { requests.nowMs() }, enabled = { pushEnabled() })
    private val radioWake = GarminRadioWake(nowMs = { requests.nowMs() })
    private val pushCover = GarminPushCover()
    private val readyNudge = ConnectIqReadyNudge(nowMs = { requests.nowMs() })
    private val devices = LinkedHashMap<String, ConnectIqDevice>()
    private val appInstalled = HashMap<Pair<String, String>, Boolean>()
    private val state = GarminLock()
    private val serverGate = GarminLock()
    private var reportedMissingBridge = false

    private val callback = object : ConnectIqCallback {
        override fun devicesReplaced(encoded: String) {
            scope.launch { onDevicesReplaced(encoded) }
        }

        override fun deviceStatus(deviceUuid: String, status: String) {
            scope.launch { onDeviceStatus(deviceUuid, status) }
        }

        override fun appStatus(deviceUuid: String, appId: String, installed: Boolean, version: Int) {
            scope.launch {
                state.withLock {
                    appInstalled[deviceUuid.uppercase() to appId.uppercase()] = installed
                }
                aapsLogger.info(LTag.GARMIN, "Connect IQ app $appId on $deviceUuid installed=$installed version=$version")
            }
        }

        override fun messageReceived(deviceUuid: String, appId: String, payloadJson: String) {
            // Android logs the incoming Connect IQ message and does nothing with it. Commands from
            // the watch are the HTTP requests, which [answer] already handles.
            aapsLogger.info(LTag.GARMIN, "Connect IQ message from $deviceUuid $appId $payloadJson")
        }

        override fun sendFinished(deviceUuid: String, appId: String, result: String, token: String) {
            scope.launch {
                val plan = push.onResult(
                    deviceUuid,
                    appId,
                    ConnectIqSendResult.fromWire(result),
                    token.toLongOrNull() ?: 0L,
                )
                if (!plan.stale) pushCover.release(1)
                if (result == "FAILURE_APP_NOT_FOUND") {
                    state.withLock {
                        appInstalled[deviceUuid.uppercase() to appId.uppercase()] = false
                    }
                }
                deliver(plan, "retry")
            }
        }
    }

    override suspend fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        preferences.observe(GarminBooleanKey.LocalHttpServer)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminIntKey.LocalHttpPort)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminStringKey.RequestKey)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { pushGlucose("key") }
        preferences.observe(GarminBooleanKey.ConnectIqPush)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { enabled ->
                if (enabled) pushGlucose("switch")
            }
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { values ->
                requests.onNewBloodGlucose(values)
                pushGlucose("bg")
            }
        rxBus.toFlow(EventAPSCalculationFinished::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN, start = CoroutineStart.UNDISPATCHED) {
                pushGlucose("aps")
            }
        setupHttpServer()
        IosRadioWake.listener = ::onRadioWake
        startConnectIq()
    }

    override suspend fun onStop() {
        IosRadioWake.listener = null
        pushCover.end()
        ConnectIqRegistry.companion?.stop()
        scope.cancel()
        aapsLogger.info(LTag.GARMIN, "Stop")
        serverGate.withLock {
            server?.close()
            server = null
        }
        super.onStop()
    }

    /**
     * A radio wake, already on the main queue. The server is checked here, never on `app.aaps.ble`.
     * The short task is only for an HTTP pull; every Connect IQ send takes its own task in [deliver].
     */
    private fun onRadioWake() {
        radioWake.onRadio(
            ensureServer = { setupHttpServer() },
            beginTask = {
                if (preferences.get(GarminBooleanKey.LocalHttpServer)) beginGarminHttpBackgroundTask()
            },
        )
    }

    private fun startConnectIq() {
        val companion = ConnectIqRegistry.companion
        if (companion == null) {
            aapsLogger.info(LTag.GARMIN, "Connect IQ bridge is not installed")
            return
        }
        val stored = ConnectIqDeviceStore.decode(preferences.get(GarminIosStringNonKey.ConnectIqDevices))
        state.withLock {
            devices.clear()
            stored.forEach { devices[it.uuid] = it }
        }
        companion.setInstallPrompt(
            title = rh.gs(SyncStrings.garmin_connect_install_title),
            message = rh.gs(SyncStrings.garmin_connect_install_message),
            accept = rh.gs(SyncStrings.garmin_connect_install_accept),
            decline = rh.gs(SyncStrings.garmin_connect_install_decline),
        )
        companion.start(callback, GarminWatchApps.byId.keys.joinToString(","))
        if (stored.isNotEmpty()) companion.restoreDevices(ConnectIqDeviceStore.encode(stored))
    }

    private fun onDevicesReplaced(encoded: String) {
        val stored = ConnectIqDeviceStore.decode(encoded)
        state.withLock {
            val previous = devices.toMap()
            devices.clear()
            stored.forEach { incoming ->
                val status = previous[incoming.uuid]?.status ?: ConnectIqDeviceStatus.UNKNOWN
                devices[incoming.uuid] = incoming.copy(status = status)
            }
        }
        preferences.put(GarminIosStringNonKey.ConnectIqDevices, ConnectIqDeviceStore.encode(stored))
        aapsLogger.info(LTag.GARMIN, "Connect IQ devices ${stored.size}")
    }

    private fun onDeviceStatus(deviceUuid: String, status: String) {
        val parsed = ConnectIqDeviceStatus.fromWire(status)
        val uuid = deviceUuid.uppercase()
        val becameReachable = state.withLock {
            val current = devices[uuid] ?: return@withLock false
            val previous = current.status
            devices[uuid] = current.copy(status = parsed)
            shouldPushOnStatus(previous, parsed)
        }
        aapsLogger.info(LTag.GARMIN, "Connect IQ $uuid $parsed")
        if (readyNudge.onStatus(uuid, parsed)) armReadyNudge(uuid)
        if (becameReachable) pushGlucose("connect")
    }

    /** One re-register if the SDK reports connected and then never says the watch is ready. */
    private fun armReadyNudge(uuid: String) {
        scope.launch {
            delay(ConnectIqReadyNudge.WAIT_MS)
            if (!readyNudge.shouldReregister(uuid)) return@launch
            aapsLogger.info(LTag.GARMIN, "Connect IQ $uuid still connected without READY, registering again")
            ConnectIqRegistry.companion?.reregisterDevice(uuid)
        }
    }

    private fun pushEnabled(): Boolean = preferences.get(GarminBooleanKey.ConnectIqPush)

    private fun pushGlucose(reason: String) {
        val (ids, denied) = state.withLock {
            val ids = devices.values.filter { it.status.canPush }.map { it.uuid }
            val denied = appInstalled.filterValues { installed -> !installed }.keys
            ids to denied
        }
        val plan = push.request(ids, GarminWatchApps.byId.keys.toList()) { device, app ->
            (device.uppercase() to app.uppercase()) !in denied
        }
        deliver(plan, reason)
    }

    private fun deliver(plan: ConnectIqPlan, reason: String) {
        plan.log?.let { aapsLogger.warn(LTag.GARMIN, it) }
        if (plan.send.isEmpty()) {
            if (plan.released > 0) pushCover.release(plan.released)
            return
        }
        val companion = ConnectIqRegistry.companion
        if (companion == null) {
            if (!reportedMissingBridge) {
                reportedMissingBridge = true
                aapsLogger.warn(LTag.GARMIN, "Connect IQ bridge is not installed, push skipped ($reason)")
            }
            plan.send.forEach { target ->
                push.onResult(target.deviceUuid, target.appId, ConnectIqSendResult.FAILURE_UNKNOWN, target.token)
            }
            if (plan.released > 0) pushCover.release(plan.released)
            return
        }
        val payload = GarminCiqJson.of(requests.getGlucoseMessage())
        val outgoing = ArrayList<Pair<ConnectIqDevice, ConnectIqTarget>>()
        for (target in plan.send) {
            val device = state.withLock { devices[target.deviceUuid] }
            if (device == null) {
                push.onResult(
                    target.deviceUuid,
                    target.appId,
                    ConnectIqSendResult.FAILURE_DEVICE_NOT_AVAILABLE,
                    target.token,
                )
                continue
            }
            outgoing.add(device to target)
        }
        if (plan.released > 0 || outgoing.isNotEmpty()) {
            val released = plan.released
            val sending = outgoing.size
            runOnMain {
                if (released > 0) pushCover.release(released)
                if (sending > 0) pushCover.cover(sending)
            }
        }
        if (outgoing.isEmpty()) return
        armWatchdog()
        aapsLogger.info(LTag.GARMIN, "Connect IQ push ${outgoing.size} ($reason)")
        for ((device, target) in outgoing) {
            companion.send(
                deviceUuid = device.uuid,
                modelName = device.modelName,
                friendlyName = device.friendlyName,
                partNumber = device.partNumber,
                appId = target.appId,
                payloadJson = payload,
                token = target.token.toString(),
            )
        }
    }

    private fun armWatchdog() {
        scope.launch {
            delay(ConnectIqPush.IN_FLIGHT_TIMEOUT_MS)
            deliver(push.reapStuck(), "watchdog")
        }
    }

    private fun setupHttpServer() {
        serverGate.withLock { setupHttpServerLocked() }
    }

    private fun setupHttpServerLocked() {
        if (preferences.get(GarminBooleanKey.LocalHttpServer)) {
            val port = preferences.get(GarminIntKey.LocalHttpPort)
            if (server?.port == port) return
            aapsLogger.info(LTag.GARMIN, "starting HTTP server on $port")
            server?.close()
            server = IosGarminHttpServer(aapsLogger, port, scope, ::answer).also { it.start() }
        } else if (server != null) {
            aapsLogger.info(LTag.GARMIN, "stopping HTTP server")
            server?.close()
            server = null
        }
    }

    /** The same rules as `GarminPlugin.requestHandler` on Android. */
    private fun answer(request: GarminHttpRequest): Pair<Int, String?> {
        val query = GarminQuery(request.query)
        if (!requests.keyAccepted(query)) {
            aapsLogger.warn(LTag.GARMIN, "Invalid AAPS Key, got '${query["key"]}' ${request.path}")
            // As on Android: tell the watch the current key, then answer slowly.
            pushGlucose("wrong-key")
            usleep(WRONG_KEY_DELAY_US)
            return HTTP_UNAUTHORIZED to "{}"
        }
        aapsLogger.info(LTag.GARMIN, "get ${request.path}?${request.query ?: ""}")
        val body = requests.answer(request.path, query)
        if (body == null) {
            aapsLogger.error(LTag.GARMIN, "request path not found '${request.path}'")
            return HTTP_NOT_FOUND to null
        }
        return HTTP_OK to body
    }

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "garmin_settings",
        title = SyncStrings.garmin,
        items = listOf(
            GarminBooleanKey.LocalHttpServer,
            GarminIntKey.LocalHttpPort,
            GarminStringKey.RequestKey,
            GarminBooleanKey.ConnectIqPush,
            GarminIosIntentKey.SelectDevices.withClick {
                val companion = ConnectIqRegistry.companion
                if (companion == null) {
                    aapsLogger.warn(LTag.GARMIN, "Connect IQ bridge is not installed, device selection skipped")
                } else {
                    companion.showDeviceSelection()
                }
            },
        ),
        icon = pluginDescription.icon
    )

    private companion object {

        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val WRONG_KEY_DELAY_US = 1_000_000u
    }
}
