package app.aaps.plugins.aps.openAPSAIMI.di

import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporterProvider
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.iosAimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Bindings that every iOS `AppScope` graph needs for the AIMI plugin and its engine.
 *
 * ### Why one shared container
 *
 * `IosAppGraph` (the shell) and `IosAimiEngineGraph` are both `AppScope` graphs, and both need
 * these two bindings. A container contributed to `AppScope` is added to every such graph, so each
 * binding lives in exactly one place. Providing them in the engine graph and again in the shell
 * would give one graph two bindings for the same type, and Metro would report a duplicate.
 *
 * Both are `@SingleIn(AppScope::class)` or stateless, so each graph keeps the same behaviour it
 * had when the engine graph provided them itself.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object IosAimiPlatformBindings {

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
}
