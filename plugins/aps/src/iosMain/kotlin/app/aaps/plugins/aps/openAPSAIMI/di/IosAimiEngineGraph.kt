package app.aaps.plugins.aps.openAPSAIMI.di

import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.database.persistence.PersistenceLayerImpl
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAimiEngine
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator
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
 * The 17 platform interfaces have no iOS implementation inside `:plugins:aps`,
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
 *
 * `PersistenceLayerImpl` is excluded on purpose. It sits in `:database:persistence`
 * commonMain and contributes a `PersistenceLayer` binding to every `AppScope` graph,
 * which would be a second one next to the instance the shell passes in. The shell owns
 * the database, so its instance is the one that must win; excluding the contributed
 * binding says that in one place instead of leaving Metro to report a duplicate.
 *
 * `AimiStorage` and `HormonitorStudyExporterProvider` are not provided here. They come from
 * `IosAimiPlatformBindings`, which is contributed to `AppScope` and so reaches this graph and the
 * shell graph from one place. Providing them here as well would be a duplicate in the shell.
 */
@DependencyGraph(AppScope::class, excludes = [PersistenceLayerImpl::class])
interface IosAimiEngineGraph {

    /** The ready-to-use engine: `AimiEngine.evaluate` over the real `determine_basal`. */
    val engine: DetermineBasalAimiEngine

    /** The underlying plugin, exposed for shells that need the full `RT` result. */
    val plugin: DetermineBasalaimiSMB2

    /**
     * The ML training coordinator, for the iOS background task. Its three inputs
     * (`AimiStorage`, `BasalNeuralLearner`, `AAPSLogger`) are all bound in this graph.
     * The shell hands it to `IosAimiBackgroundTasks.install` once the graph is built.
     */
    val mlTrainingCoordinator: BasalMlTrainingCoordinator

    @Provides
    @SingleIn(AppScope::class)
    fun engine(plugin: DetermineBasalaimiSMB2): DetermineBasalAimiEngine =
        DetermineBasalAimiEngine(plugin)

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
         * @param decimalFormatter From `IosAppGraph.decimalFormatter`.
         * @param constraintsChecker From the shell's `ConstraintsCheckerImpl`. It must be the
         *   shell's own: a checker that knows no plugins would apply no limits, and "no limit"
         *   is the unsafe direction for every constraint AAPS has.
         * @param keyValueStore From `IosPlatformBindings.keyValueStore()`, which is `IosSp` over
         *   `NSUserDefaults`.
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
            @Provides decimalFormatter: DecimalFormatter,
            @Provides constraintsChecker: ConstraintsChecker,
            @Provides keyValueStore: KeyValueStore,
        ): IosAimiEngineGraph
    }
}
