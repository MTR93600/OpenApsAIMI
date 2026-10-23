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
 * Contributed to `AppScope`, so `IosAppGraph` - the one iOS app graph - gets them without naming
 * them. There used to be a second graph for the engine; it was removed, because it built a second
 * engine and learner that the loop never used.
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
