package app.aaps.plugins.sync.liveactivity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Widgets
import app.aaps.core.data.model.GV
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.appInitializedFlow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.EnforcedState
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.widget.WidgetStateExport
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.sync.SyncStrings
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
import kotlinx.coroutines.flow.drop

/**
 * The iOS Live Activity: glucose on the lock screen, in the Dynamic Island and in the Apple Watch
 * Smart Stack.
 *
 * Kotlin decides, Swift draws. On every new glucose value and every loop result this plugin builds
 * the shared widget state (`WidgetStateBuilder` in `:ui`, the same rule as the Android widgets) and
 * hands it to Swift as JSON through [LiveActivityCompanion]. [LiveActivityPublisher] has the rules
 * for when.
 *
 * Local updates only, no push server: the activity is only as fresh as the last time iOS let the app
 * run. That is why the JSON carries the reading time and `staleAt`, and why Swift greys out and
 * strikes through an old value by itself.
 *
 * The plugin is always on (enforced). The one switch the user sees is
 * [LiveActivityBooleanKey.LiveActivityEnabled], off by default.
 *
 * ⚠️ ASYNC IMPACT: the collectors run on [scope]. Swift calls back on the main thread; the work is
 * handed to [scope].
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(380)
@SingleIn(AppScope::class)
@Inject
class LiveActivityPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    notificationManager: NotificationManager,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus,
    private val widgetStateExport: WidgetStateExport,
    private val dateUtil: DateUtil,
    private val config: Config,
) : PluginBaseWithPreferences(
    pluginDescription = PluginDescription()
        .mainType(PluginType.SYNC)
        .icon(Icons.Filled.Widgets)
        .pluginName(SyncStrings.live_activity)
        .description(SyncStrings.live_activity_description)
        .enforce(EnforcedState.Enabled),
    ownPreferences = LiveActivityBooleanKey.entries,
    aapsLogger, rh, preferences, notificationManager
) {

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var publisher: LiveActivityPublisher? = null

    override suspend fun onStart() {
        super.onStart()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val publisher = LiveActivityPublisher(
            scope = scope,
            export = widgetStateExport,
            appInitialized = config.appInitializedFlow,
            settings = ::readSettings,
            nowMs = { dateUtil.now() },
            log = { aapsLogger.info(LTag.WIDGET, it) },
        ).also { publisher = it }

        preferences.observe(LiveActivityBooleanKey.LiveActivityEnabled)
            .drop(1)
            .collectResilient(scope, aapsLogger, LTag.WIDGET) { publisher.onEnabledChanged(it) }
        for (key in listOf(
            LiveActivityBooleanKey.LiveActivityShowChart,
            LiveActivityBooleanKey.LiveActivityShowIobCob,
            LiveActivityBooleanKey.LiveActivityShowLoop
        )) {
            preferences.observe(key)
                .drop(1)
                .collectResilient(scope, aapsLogger, LTag.WIDGET) { publisher.onShowChanged() }
        }
        persistenceLayer.observeChanges(GV::class)
            .collectResilient(scope, aapsLogger, LTag.WIDGET) { publisher.request("bg") }
        rxBus.toFlow(EventAPSCalculationFinished::class)
            .collectResilient(scope, aapsLogger, LTag.WIDGET, start = CoroutineStart.UNDISPATCHED) { publisher.request("aps") }
        publisher.start()
    }

    override suspend fun onStop() {
        publisher?.stop()
        publisher = null
        scope.cancel()
        super.onStop()
    }

    private fun readSettings() = LiveActivitySettings(
        enabled = preferences.get(LiveActivityBooleanKey.LiveActivityEnabled),
        showChart = preferences.get(LiveActivityBooleanKey.LiveActivityShowChart),
        showIobCob = preferences.get(LiveActivityBooleanKey.LiveActivityShowIobCob),
        showLoop = preferences.get(LiveActivityBooleanKey.LiveActivityShowLoop),
    )

    override fun getPreferenceScreenContent() = PreferenceSubScreenDef(
        key = "live_activity_settings",
        title = SyncStrings.live_activity,
        items = LiveActivityBooleanKey.entries,
        icon = pluginDescription.icon
    )
}
