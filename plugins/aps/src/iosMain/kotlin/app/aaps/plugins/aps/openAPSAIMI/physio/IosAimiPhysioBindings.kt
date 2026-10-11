package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPhysioSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Binds the HealthKit repository to its contracts, mirroring `AimiPhysioBindings` on Android.
 *
 * Explicit `@Provides` instead of `@ContributesBinding` on the class, for the same reason as there:
 * [IosHealthKitPhysioRepository] implements two interfaces and Metro cannot pick one. Both
 * providers hand back the same singleton, so the cache inside it is shared, as on Android.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object IosAimiPhysioBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideAimiPhysioSource(repository: IosHealthKitPhysioRepository): AimiPhysioSource = repository

    @Provides
    @SingleIn(AppScope::class)
    fun provideAimiPhysioDataSource(repository: IosHealthKitPhysioRepository): AimiPhysioDataSource = repository
}
