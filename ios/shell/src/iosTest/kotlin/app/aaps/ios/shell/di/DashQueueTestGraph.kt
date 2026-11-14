package app.aaps.ios.shell.di

import app.aaps.core.interfaces.alerts.LocalAlertUtils
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.di.APS
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.notifications.SystemNotificationPlatform
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.CrashReporter
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.di.CoreObjectsGraph
import app.aaps.database.AppRepository
import app.aaps.database.di.IosAppDatabaseBuilder
import app.aaps.implementation.maintenance.KeepAliveChecks
import app.aaps.implementation.plugin.PluginStore
import app.aaps.plugins.constraints.bgQualityCheck.BgQualityCheckPlugin
import app.aaps.plugins.sync.nsclientV3.ws.NsSocketFactory
import app.aaps.pump.omnipod.dashctl.ios.plugin.CoreBluetoothDashPlatform
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosDashDriverHost
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosDashPlatform
import app.aaps.pump.omnipod.dashctl.ios.plugin.IosOmnipodDashPlugin
import app.aaps.shared.clientbindings.ClientGraphBindings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The app graph of [IosAppGraph] (the same `AppScope` contributions: the real command queue, the
 * real `PumpSync`, the real config builder and plugin list, the real Dash plugin), with two things
 * left out:
 * - the iPhone side of the Dash plugin, [CoreBluetoothDashPlatform]. The test gives its own
 *   [IosDashPlatform] instead, a simulated pod behind a fake BLE central, so the plugin cannot tell it
 *   from a real pod;
 * - [IosPlatformBindings], only for its system notifications: `UNUserNotificationCenter` stops a test
 *   binary (it has no app bundle). Every other binding of it is given here by the same function, and
 *   the notifications go to [RecordingSystemNotifications], so a test can read them.
 *
 * Its own database file, so a test run never touches the app's data.
 */
@DependencyGraph(AppScope::class, excludes = [CoreBluetoothDashPlatform::class, IosPlatformBindings::class])
interface DashQueueTestGraph {

    val config: Config
    val preferences: Preferences
    val logger: AAPSLogger
    val commandQueue: CommandQueue
    val activePlugin: ActivePlugin
    val configBuilder: ConfigBuilder
    val pluginStore: PluginStore
    val persistenceLayer: PersistenceLayer
    val profileFunction: ProfileFunction

    /** The plugin list of the app, as `IosAppStartup` registers it. */
    val contributedPlugins: Map<Int, PluginBase>

    @APS
    val contributedApsPlugins: Map<Int, PluginBase>

    val omnipodDashPump: IosOmnipodDashPlugin

    /** The same host the plugin got: the wizard pod and the rules of the plugin. */
    val dashHost: IosDashDriverHost

    /** The system notifications the app posted. */
    val systemNotifications: RecordingSystemNotifications

    @Provides
    @SingleIn(AppScope::class)
    fun systemNotificationPlatform(recording: RecordingSystemNotifications): SystemNotificationPlatform = recording

    // The rest of IosPlatformBindings, unchanged.
    @Provides
    @SingleIn(AppScope::class)
    fun logger(logConfig: () -> L): AAPSLogger = IosPlatformBindings.logger(logConfig)

    @Provides
    @SingleIn(AppScope::class)
    fun keyValueStore(): KeyValueStore = IosPlatformBindings.keyValueStore()

    @Provides
    @SingleIn(AppScope::class)
    fun dateUtil(): DateUtil = IosPlatformBindings.dateUtil()

    @Provides
    @SingleIn(AppScope::class)
    fun config(): Config = IosPlatformBindings.config()

    @Provides
    fun bgQualityCheck(plugin: BgQualityCheckPlugin): BgQualityCheck = IosPlatformBindings.bgQualityCheck(plugin)

    @Provides
    @SingleIn(AppScope::class)
    fun keepAliveChecks(
        aapsLogger: AAPSLogger,
        localAlertUtils: LocalAlertUtils,
        config: Config,
        iobCobCalculator: IobCobCalculator,
        loop: Loop,
        dateUtil: DateUtil,
        activePlugin: ActivePlugin,
        profileFunction: ProfileFunction,
        rxBus: RxBus,
        commandQueue: CommandQueue,
        ch: ConcentrationHelper,
        preferences: Preferences,
        rh: TextResolver
    ): KeepAliveChecks = IosPlatformBindings.keepAliveChecks(
        aapsLogger, localAlertUtils, config, iobCobCalculator, loop, dateUtil, activePlugin, profileFunction, rxBus, commandQueue, ch, preferences, rh
    )

    @Provides
    @SingleIn(AppScope::class)
    fun repository(): AppRepository = IosAppDatabaseBuilder().provideAppRepository("aaps-ios-dash-queue-test.db")

    @DependencyGraph.Factory
    fun interface Factory {

        fun create(
            @Includes coreObjects: CoreObjectsGraph,
            @Includes clientBindings: ClientGraphBindings,
            @Provides nsSocketFactory: NsSocketFactory,
            @Provides crashReporter: CrashReporter,
            /** The simulated pod, in place of CoreBluetooth, the Keychain and the real time. */
            @Provides dashPlatform: IosDashPlatform,
        ): DashQueueTestGraph
    }
}
