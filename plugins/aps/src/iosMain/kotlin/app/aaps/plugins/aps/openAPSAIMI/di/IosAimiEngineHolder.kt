package app.aaps.plugins.aps.openAPSAIMI.di

import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAimiEngine
import kotlin.concurrent.Volatile

/**
 * Where the real AIMI engine lives once the iOS shell has built it.
 *
 * The shell installs the graph at startup (see `aapsAppViewController`): building it needs the
 * shell's own bindings, and `:plugins:aps` cannot depend on the shell, so the graph is handed in
 * rather than discovered. iOS scene code reads [engine] instead of reaching into the shell.
 *
 * Fail-visible by design: [engine] is null until [install] runs, and stays null if the graph
 * failed to build at startup. Scenes treat null as "engine not wired" and keep the neutral
 * engine as their fallback - a missing engine must never read as a working one.
 */
object IosAimiEngineHolder {

    @Volatile
    private var graph: IosAimiEngineGraph? = null

    /**
     * Installs the engine graph. Call once at app startup, after the plugin registry is
     * initialized - some of the engine's transitive dependencies read it at construction time.
     */
    fun install(graph: IosAimiEngineGraph) {
        this.graph = graph
    }

    /** Drops the installed graph. Tests only. */
    fun clear() {
        graph = null
    }

    /**
     * The real engine (`AimiEngine.evaluate` over `determine_basal`), or null when the shell
     * never installed the graph or its construction failed.
     */
    val engine: DetermineBasalAimiEngine?
        get() = graph?.engine
}
