package app.aaps.plugins.sync.homewidget

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
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.widget.WidgetStateExport
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

/**
 * The iOS home and lock screen widgets: the same content as the Android widgets, from the same shared
 * widget state (`WidgetStateBuilder` in `:ui`).
 *
 * On every new glucose value and every loop result this plugin builds the state with the chart and the
 * details and hands it to Swift through [HomeWidgetCompanion], which saves it in the App Group
 * container for the widget extension. [HomeWidgetPublisher] has the rules for when.
 *
 * Always on and not in the plugin list, like the Android widgets, which need no switch either: the user
 * chooses by adding a widget to the home screen.
 *
 * ⚠️ ASYNC IMPACT: the collectors run on [scope].
 */
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(381)
@SingleIn(AppScope::class)
@Inject
class HomeWidgetPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    notificationManager: NotificationManager,
    private val persistenceLayer: PersistenceLayer,
    private val rxBus: RxBus,
    private val widgetStateExport: WidgetStateExport,
    private val dateUtil: DateUtil,
    private val config: Config,
) : PluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(SyncStrings.home_widgets)
        .description(SyncStrings.home_widgets_description)
        .enforce(EnforcedState.Enabled)
        .showInList { false },
    aapsLogger, rh, notificationManager
) {

    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var publisher: HomeWidgetPublisher? = null

    override suspend fun onStart() {
        super.onStart()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val publisher = HomeWidgetPublisher(
            scope = scope,
            export = widgetStateExport,
            appInitialized = config.appInitializedFlow,
            nowMs = { dateUtil.now() },
            log = { aapsLogger.info(LTag.WIDGET, it) },
        ).also { publisher = it }

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
}
