package app.aaps.plugins.sync.garmin

import android.content.Context
import androidx.annotation.VisibleForTesting
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
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.keys.interfaces.Preferences
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import java.net.HttpURLConnection
import java.net.SocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration

/** Support communication with Garmin devices.
*
* This plugin supports sending glucose values to Garmin devices and receiving
* carbs, heart rate, steps, temporary targets, AIMI `/mode` (sport, and the FCL temporary target)
* and pump disconnect events from the device. It communicates via HTTP on localhost or Garmin's native CIQ library.
*
* On a client it only shows values: carbs and pump connect or disconnect from the
* watch are refused there, see `GarminRequests.onPostCarbs`.
*/
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(370)
@SingleIn(AppScope::class)
@Inject
class GarminPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    private val context: Context,
    loopHub: LoopHub,
    private val persistenceLayer: PersistenceLayer,
    config: Config,
    notificationManager: NotificationManager
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(IcPluginGarmin)
        .pluginName(SyncStrings.garmin)
        .description(SyncStrings.garmin_description),
    ownPreferences = GarminStringKey.entries + GarminBooleanKey.entries + GarminIntKey.entries +
        GarminIntNonKey.entries + GarminLongNonKey.entries,
    aapsLogger, rh, preferences, notificationManager
) {

    /** HTTP Server for local HTTP server communication (device app requests values) .*/
    private var server: HttpServer? = null

    /** The answers to the watch, shared with the iPhone. */
    @VisibleForTesting
    val requests = GarminRequests(aapsLogger, rh, preferences, loopHub, persistenceLayer, config, notificationManager)

    /** The current time. Setting it sets the time of [requests] too. */
    @VisibleForTesting
    var clock: Clock = Clock.systemUTC()
        set(value) {
            field = value
            requests.nowMs = { value.millis() }
        }

    @VisibleForTesting
    var garminMessengerField: GarminMessenger? = null
    val garminMessenger: GarminMessenger
        get() {
            return synchronized(this) {
                garminMessengerField ?: createGarminMessenger().also { garminMessengerField = it }
            }
        }

    private fun resetGarminMessenger() {
        synchronized(this) {
            garminMessengerField?.dispose()
            garminMessengerField = null
        }
    }

    /** Garmin ConnectIQ application id for native communication. Phone pushes values. */
    private val glucoseAppIds = mapOf(
        "C9E90EE7E6924829A8B45E7DAFFF5CB4" to "GlucoseWatch_Dev",
        "1107CA6C2D5644B998D4BCB3793F2B7C" to "GlucoseDataField_Dev",
        "928FE19A4D3A4259B50CB6F9DDAF0F4A" to "GlucoseWidget_Dev",
        "662DFCF7F5A147DE8BD37F09574ADB11" to "GlucoseWatch",
        "815C7328C21248C493AD9AC4682FE6B3" to "GlucoseDataField",
        "4BDDCC1740084A1FAB83A3B2E2FCF55B" to "GlucoseWidget",
    )

    @VisibleForTesting
    var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val garminAapsKey get() = preferences.get(GarminStringKey.RequestKey)

    private fun setupGarminMessenger() {
        resetGarminMessenger()
        createGarminMessenger()
    }

    private fun createGarminMessenger(): GarminMessenger {
        val enableDebug = false
        aapsLogger.info(LTag.GARMIN, "initialize IQ messenger in debug=$enableDebug")
        return GarminMessenger(
            aapsLogger, context, glucoseAppIds, { _, _ -> }, true, enableDebug
        )
    }

    override suspend fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.GARMIN, "start")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        preferences.observe(GarminBooleanKey.LocalHttpServer)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminIntKey.LocalHttpPort)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { setupHttpServer() }
        preferences.observe(GarminStringKey.RequestKey)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.GARMIN) { sendPhoneAppMessage() }
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN, block = ::onNewBloodGlucose)
        setupHttpServer()
        if (garminAapsKey.isNotEmpty())
            setupGarminMessenger()
    }

    private fun setupHttpServer() {
        setupHttpServer(Duration.ZERO)
    }

    @VisibleForTesting
    fun setupHttpServer(wait: Duration) {
        if (preferences.get(GarminBooleanKey.LocalHttpServer)) {
            val port = preferences.get(GarminIntKey.LocalHttpPort)
            if (server != null && server?.port == port) return
            aapsLogger.info(LTag.GARMIN, "starting HTTP server on $port")
            server?.close()
            server = HttpServer(aapsLogger, port).apply {
                registerEndpoint("/get", requestHandler(::onGetBloodGlucose))
                registerEndpoint("/carbs", requestHandler(::onPostCarbs))
                registerEndpoint("/bolus", requestHandler(::onPostBolus))
                registerEndpoint("/temptarget", requestHandler(::onPostTempTarget))
                registerEndpoint("/mode", requestHandler(::onPostMode))
                registerEndpoint("/connect", requestHandler(::onConnectPump))
                registerEndpoint("/sgv.json", requestHandler(::onSgv))
                awaitReady(wait)
            }
        } else if (server != null) {
            aapsLogger.info(LTag.GARMIN, "stopping HTTP server")
            server?.close()
            server = null
        }
    }

    override suspend fun onStop() {
        scope.cancel()
        garminMessengerField?.dispose()
        aapsLogger.info(LTag.GARMIN, "Stop")
        server?.close()
        server = null
        super.onStop()
    }

    /** New glucose values were stored: wakes a watch request that waits for them. */
    @VisibleForTesting
    fun onNewBloodGlucose(glucoseValues: List<GV>) = requests.onNewBloodGlucose(glucoseValues)

    @VisibleForTesting
    fun onConnectDevice(device: GarminDevice) {
        if (garminAapsKey.isNotEmpty()) {
            aapsLogger.info(LTag.GARMIN, "onConnectDevice $device sending glucose")
            sendPhoneAppMessage(device)
        }
    }

    private fun sendPhoneAppMessage(device: GarminDevice) {
        garminMessenger.sendMessage(device, getGlucoseMessage())
    }

    private fun sendPhoneAppMessage() {
        garminMessenger.sendMessage(getGlucoseMessage())
    }

    @VisibleForTesting
    fun getGlucoseMessage() = requests.getGlucoseMessage()

    /** Gets the last 2+ hours of glucose values. */
    @VisibleForTesting
    fun getGlucoseValues(): List<GV> = requests.getGlucoseValues()

    @VisibleForTesting
    fun requestHandler(action: (URI) -> CharSequence) = { caller: SocketAddress, uri: URI, _: String? ->
        val query = GarminQuery(uri.query)
        if (!requests.keyAccepted(query)) {
            aapsLogger.warn(LTag.GARMIN, "Invalid AAPS Key from $caller, got '${query["key"]}' want '$garminAapsKey' $uri")
            sendPhoneAppMessage()
            Thread.sleep(1000L)
            HttpURLConnection.HTTP_UNAUTHORIZED to "{}"
        } else {
            aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri")
            HttpURLConnection.HTTP_OK to action(uri).also {
                aapsLogger.info(LTag.GARMIN, "get from $caller resp , req: $uri, result: $it")
            }
        }
    }

    // The answers themselves are in GarminRequests; these keep the Android server and the tests on URI.

    @VisibleForTesting
    fun onGetBloodGlucose(uri: URI): CharSequence = requests.onGetBloodGlucose(GarminQuery(uri.query))

    @VisibleForTesting
    fun receiveHeartRate(msg: Map<String, Any>, test: Boolean) = requests.receiveHeartRate(msg, test)

    @VisibleForTesting
    fun receiveHeartRate(uri: URI) = requests.receiveHeartRate(GarminQuery(uri.query))

    @VisibleForTesting
    fun receiveSteps(msg: Map<String, Any>, test: Boolean) = requests.receiveSteps(msg, test)

    @VisibleForTesting
    fun receiveSteps(uri: URI) = requests.receiveSteps(GarminQuery(uri.query))

    @VisibleForTesting
    fun onPostCarbs(uri: URI): CharSequence = requests.onPostCarbs(GarminQuery(uri.query))

    @VisibleForTesting
    fun onPostBolus(uri: URI): CharSequence = requests.onPostBolus(GarminQuery(uri.query))

    @VisibleForTesting
    fun onPostTempTarget(uri: URI): CharSequence = requests.onPostTempTarget(GarminQuery(uri.query))

    @VisibleForTesting
    fun onPostMode(uri: URI): CharSequence = requests.onPostMode(GarminQuery(uri.query))

    @VisibleForTesting
    fun onConnectPump(uri: URI): CharSequence = requests.onConnectPump(GarminQuery(uri.query))

    @VisibleForTesting
    fun onSgv(uri: URI): CharSequence = requests.onSgv(GarminQuery(uri.query))

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "garmin_settings",
        title = SyncStrings.garmin,
        items = listOf(
            GarminBooleanKey.LocalHttpServer,
            GarminIntKey.LocalHttpPort,
            GarminStringKey.RequestKey

        ),
        icon = pluginDescription.icon
    )

}
