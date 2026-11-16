package app.aaps.plugins.aps.openAPSAIMI.di

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporterProvider
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHormonitorStudyExporterProvider
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
     * The Hormonitor study files, written to the AIMI storage folder as on Android (they used to be
     * absent on iOS: this returned null). Single, because the exporter keeps day counters.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun hormonitorStudyExporterProvider(
        aapsLogger: AAPSLogger,
        preferences: Preferences,
        storage: AimiStorage,
    ): HormonitorStudyExporterProvider = IosHormonitorStudyExporterProvider(aapsLogger, preferences, storage)
}
