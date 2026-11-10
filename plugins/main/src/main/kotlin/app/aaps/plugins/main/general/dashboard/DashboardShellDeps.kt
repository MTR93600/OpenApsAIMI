package app.aaps.plugins.main.general.dashboard

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.overview.graph.OverviewDataCache
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorNotificationManager
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusLiveData
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.overview.graphData.GraphData
import app.aaps.plugins.main.general.overview.notifications.NotificationUiBinder
import javax.inject.Provider

/**
 * Bundles dependencies used by [DashboardShellController] (view wiring). [OverviewViewModel.Factory]
 * is provided separately via Dagger with the full model dependency graph.
 */
data class DashboardShellDeps(
    val overviewViewModelFactory: OverviewViewModel.Factory,
    val resourceHelper: ResourceHelper,
    val decimalFormatter: DecimalFormatter,
    val preferences: Preferences,
    val dateUtil: DateUtil,
    val loop: Loop,
    val activePlugin: ActivePlugin,
    val rxBus: RxBus,
    val aapsSchedulers: AapsSchedulers,
    val fabricPrivacy: FabricPrivacy,
    val overviewData: OverviewData,
    val overviewMenus: OverviewMenus,
    val graphDataProvider: Provider<GraphData>,
    val config: Config,
    val protectionCheck: ProtectionCheck,
    val uiInteraction: UiInteraction,
    val aapsLogger: AAPSLogger,
    val xDripSource: XDripSource,
    val dexcomBoyda: DexcomBoyda,
    val notificationUiBinder: NotificationUiBinder,
    val auditorStatusLiveData: AuditorStatusLiveData,
    val auditorNotificationManager: AuditorNotificationManager,
    val overviewDataCache: Provider<OverviewDataCache>,
    val persistenceLayer: PersistenceLayer,
)
