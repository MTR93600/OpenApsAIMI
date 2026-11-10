package app.aaps.ios.shell

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.ios.shell.di.GeneratedStringOwners
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * What has to happen before the first screen is composed - the iOS counterpart of `MainApp`.
 *
 * Only one step so far, and it is not optional. `PluginStore.plugins` is a `lateinit`, and the very
 * first thing that asks for the active pump reads it: `ProfileEditorViewModel` does so in its own
 * constructor, from a coroutine, and an exception thrown there is unhandled and **aborts the
 * process**. That was the crash on the first run of this - the app started, drew nothing and died.
 *
 * Run before the composition rather than beside it. Registering plugins is a list assignment, so
 * there is nothing to gain from doing it in the background, and everything to lose: a view model
 * built while the list was still empty would fail exactly as before.
 *
 * ## Waiting for the plugins, without blocking the main thread
 *
 * `ConfigBuilder.initialize` only schedules each plugin's `onStart`. Android's `MainApp` waits for
 * those jobs (at most 30 s) before it starts anything that reads plugin state. `run()` is called on
 * the iOS main thread, where the same wait would freeze the screen for as long as the slowest
 * driver takes. So the wait happens in [startupScope] instead, and [run] returns at once. When the
 * plugins have started, or 30 s have passed:
 *
 * 1. [startMasterServices] starts what `MainApp` starts on an Android master: the running-mode
 *    reconciler and its expiry scheduler, the profile switch scheduler, the automation runtime and
 *    the keep-alive checks. They are started in [servicesContext], the main thread in the app, which
 *    is where they were started before and where `CLLocationManager` and friends expect to be made.
 * 2. [afterStart] does the rest of `MainApp.doInit` that a master needs: the version change record,
 *    the "AAPS started" note and `EventAppInitialized`.
 *
 * The screen may be shown before step 1, as on Android after the splash; nothing it shows needs the
 * master services to be running.
 *
 * Before the plugins start, [beforePluginsStart] runs. It is for work that must finish before any
 * plugin can act - today the Nightscout role change, which has to move the upload marks before the
 * first upload.
 *
 * ## What Android also does here, and this does not
 *
 * `MainApp` also migrates preferences, vacuums the database and runs data migrations. Those move
 * data written by older Android versions; an iOS install has none. `IosConfig` reports
 * initialization done from the start, so the splash does not appear at all.
 */
internal class IosAppStartup(
    private val aapsLogger: AAPSLogger,
    private val registry: PluginRegistry,
    private val contributedPlugins: Map<Int, PluginBase>,
    /** The `@APS` bucket. Merged only when [mergeApsPlugins], like `MetroGraphs.allPlugins`. */
    private val apsPlugins: Map<Int, PluginBase> = emptyMap(),
    private val mergeApsPlugins: Boolean = false,
    /** Starts the shared periodic housekeeping. A lambda so a test can run this without building it. */
    private val startPeriodicWork: () -> Unit,
    /** Starts the services a master phone runs. A lambda for the same reason. */
    private val startMasterServices: () -> Unit = {},
    /** Runs after the plugins are registered and before they start. */
    private val beforePluginsStart: () -> Unit = {},
    /** Runs after the master services, off the main thread. */
    private val afterStart: suspend () -> Unit = {},
    /**
     * Where the wait for the plugins runs. Unconfined by default, so a test with no plugin jobs runs
     * every step before [run] returns.
     */
    private val startupScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    /** Where [startMasterServices] runs. The main thread in the app; the caller's thread in a test. */
    private val servicesContext: CoroutineContext = EmptyCoroutineContext
) {

    fun run() {
        // Before anything else: a screen that renders before this has run shows string names rather
        // than text, and the plugin list below builds plugin descriptions that carry names.
        GeneratedStringOwners.registerAll()

        // Sorted by the order key each plugin registers itself with, which is the order the plugin
        // list is shown in and the order defaults are picked in.
        val plugins = mergedPlugins().entries.sortedBy { it.key }.map { it.value }
        registry.register(plugins)
        aapsLogger.debug(LTag.CORE, "Registered ${plugins.size} plugins on iOS")

        // Loads every plugin's stored enabled state and picks the active one in each category - the
        // pump, the sensitivity, the smoothing and so on. Registering the list is not enough on its
        // own: until this runs, `activePumpInternal` has nothing to return and throws "No pump
        // selected", which was the second crash on the way here.
        //
        // `ConfigBuilderImpl` is shared, so this is the same `initialize()` Android's `MainApp` and
        // the desktop `Main` call, rather than a copy of part of what it does. Calling only the
        // category check - which is what this did before - left every enabled plugin unstarted.
        beforePluginsStart()
        val started = registry.initializeConfig()

        // The periodic housekeeping Android gets from KeepAliveWorker: the missed-reading alarm,
        // snooze shortening, and log and database trimming. The work is shared; only the trigger is
        // not, because there is no WorkManager here. Without this the AlertMissedBgReading
        // preference was drawn on this platform and did nothing, and the database never shrank.
        startPeriodicWork()

        startupScope.launch {
            if (withTimeoutOrNull(PLUGIN_START_WAIT) { started.joinAll() } == null)
                aapsLogger.warn(LTag.CORE, "Plugins did not finish starting within $PLUGIN_START_WAIT")
            withContext(servicesContext) { startMasterServices() }
            try {
                afterStart()
            } catch (e: Exception) {
                aapsLogger.error(LTag.CORE, "Start-up step after the plugins failed", e)
            }
            aapsLogger.debug(LTag.CORE, "Plugins and master services started")
        }

        aapsLogger.debug(LTag.CORE, "Plugins registered and starting, showing the UI")
    }

    /**
     * The same rule as Android's `mergePlugins`: a later bucket wins an order key, and a clash is
     * logged rather than thrown, because a wrong plugin list must not stop the app from starting.
     */
    private fun mergedPlugins(): Map<Int, PluginBase> {
        if (!mergeApsPlugins) return contributedPlugins
        val merged = LinkedHashMap(contributedPlugins)
        for ((key, plugin) in apsPlugins) {
            merged[key]?.let { previous ->
                aapsLogger.error(LTag.CORE, "PLUGIN LIST: order key $key used by both ${previous::class.simpleName} and @APS ${plugin::class.simpleName} - one of them is dropped")
            }
            merged[key] = plugin
        }
        return merged
    }

    private companion object {

        /** The same bound as `MainApp`. */
        val PLUGIN_START_WAIT = 30.seconds
    }
}
