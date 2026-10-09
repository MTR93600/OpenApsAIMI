package app.aaps.plugins.aps.openAPSAIMI.di

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAimiEngine
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporterProvider
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.iosAimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Metro object graph that assembles the real AIMI engine on iOS.
 *
 * [DetermineBasalaimiSMB2] declares its 36 dependencies through `@Inject`
 * (constructor + field injection). Metro resolves everything that lives in
 * commonMain automatically: `@Inject` constructors and `@ContributesBinding`
 * implementations are discovered without manual wiring.
 *
 * The 14 platform interfaces have no iOS implementation inside `:plugins:aps`,
 * so they arrive as factory parameters. The iOS shell (`IosAppGraph`) exposes
 * all of them; the shell wires the two graphs by passing its own bindings
 * into [Factory.create] (see the KDoc there for the mapping).
 *
 * This is a plain `@DependencyGraph`, not a `@GraphExtension` of `IosAppGraph`:
 * `:plugins:aps` cannot depend on `:ios:shell` (the shell depends on the
 * plugin, not the other way round). The shell wires the two by passing its own
 * bindings into [Factory.create].
 *
 * Testable by design: every binding is explicit, no hidden singletons. A test
 * creates the graph with fakes through the same factory.
 */
@DependencyGraph(AppScope::class)
interface IosAimiEngineGraph {

    /** The ready-to-use engine: `AimiEngine.evaluate` over the real `determine_basal`. */
    val engine: DetermineBasalAimiEngine

    /** The underlying plugin, exposed for shells that need the full `RT` result. */
    val plugin: DetermineBasalaimiSMB2

    @Provides
    @SingleIn(AppScope::class)
    fun engine(plugin: DetermineBasalaimiSMB2): DetermineBasalAimiEngine =
        DetermineBasalAimiEngine(plugin)

    /**
     * File storage for the learners (CSV journal, model weights).
     * `iosAimiStorage()` assembles the existing `DirectoryAimiStorage` over
     * `Documents/AAPS`; the factory is a plain function, so it needs this
     * explicit binding for Metro to see it.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun aimiStorage(): AimiStorage = iosAimiStorage()

    /**
     * Study telemetry is unavailable on iOS. `null` is a normal answer by
     * design: the interface documents that a platform with no implementation
     * returns null, and the engine treats it as "telemetry off".
     */
    @Provides
    fun hormonitorStudyExporterProvider(): HormonitorStudyExporterProvider =
        object : HormonitorStudyExporterProvider {
            override fun exporter(): HormonitorStudyExporter? = null
        }

    @DependencyGraph.Factory
    fun interface Factory {

        /**
         * @param profileUtil From `IosAppGraph.profileUtil`.
         * @param fabricPrivacy From `IosAppGraph.fabricPrivacy`.
         * @param preferences From `IosAppGraph.preferences`.
         * @param uiInteraction From `IosAppGraph.uiInteraction`.
         * @param notificationManager From `IosAppGraph.notificationManager`.
         * @param persistenceLayer From `IosAppGraph.persistenceLayer`.
         * @param tddCalculator From `IosAppGraph.tddCalculator`.
         * @param tirCalculator From `IosAppGraph.tirCalculator`.
         * @param dateUtil From `IosAppGraph.dateUtil`.
         * @param profileFunction From `IosAppGraph.profileFunction`.
         * @param iobCobCalculator From `IosAppGraph.iobCobCalculator`.
         * @param activePlugin From `IosAppGraph.activePlugin`.
         * @param textResolver From `IosAppGraph.textResolver`.
         * @param aapsLogger From `IosAppGraph.logger`.
         */
        fun create(
            @Provides profileUtil: ProfileUtil,
            @Provides fabricPrivacy: FabricPrivacy,
            @Provides preferences: Preferences,
            @Provides uiInteraction: UiInteraction,
            @Provides notificationManager: NotificationManager,
            @Provides persistenceLayer: PersistenceLayer,
            @Provides tddCalculator: TddCalculator,
            @Provides tirCalculator: TirCalculator,
            @Provides dateUtil: DateUtil,
            @Provides profileFunction: ProfileFunction,
            @Provides iobCobCalculator: IobCobCalculator,
            @Provides activePlugin: ActivePlugin,
            @Provides textResolver: TextResolver,
            @Provides aapsLogger: AAPSLogger,
        ): IosAimiEngineGraph
    }
}
