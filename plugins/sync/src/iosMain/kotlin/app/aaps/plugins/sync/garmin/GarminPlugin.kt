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
import platform.posix.usleep

/**
 * Garmin on the iPhone: the same watch face, data field and widget as on Android, answered by a local
 * HTTP server ([IosGarminHttpServer]).
 *
 * Everything the watch gets back is decided in [GarminRequests], shared with Android's
 * `GarminPlugin`, which also has the same settings, keys and place in the plugin list.
 *
 * It has the same class name as the Android plugin on purpose: the "enabled" setting is stored under
 * `ConfigBuilder_Enabled_SYNC_<class name>` (`ConfigBuilderImpl.composedKeyFor`), so with the same name
 * a settings file imported from an Android phone turns Garmin on here too. What this side
 * does not have yet is the ConnectIQ messenger: Android sends the request key to the watch when the
 * watch sends a wrong one. Until the ConnectIQ iPhone SDK is added (see
 * `docs/kmp-migration/garmin-ios.md`, phase 3), a watch with a wrong key only gets "401".
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

    /** The answers to the watch, shared with Android. */
    val requests = GarminRequests(aapsLogger, rh, preferences, loopHub, persistenceLayer, config, notificationManager)

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var server: IosGarminHttpServer? = null

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
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, aapsLogger, LTag.GARMIN, block = requests::onNewBloodGlucose)
        setupHttpServer()
    }

    override suspend fun onStop() {
        scope.cancel()
        aapsLogger.info(LTag.GARMIN, "Stop")
        server?.close()
        server = null
        super.onStop()
    }

    private fun setupHttpServer() {
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
            // As on Android: a wrong key is answered slowly, so a watch cannot try many keys quickly.
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
            GarminStringKey.RequestKey
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
