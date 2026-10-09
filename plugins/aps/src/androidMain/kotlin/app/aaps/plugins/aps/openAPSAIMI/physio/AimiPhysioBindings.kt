package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPhysioSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Binds the Health Connect repository to its contracts.
 *
 * Explicit `@Provides` (instead of `@ContributesBinding` on the class) because
 * `AIMIPhysioDataRepositoryMTR` implements two interfaces; Metro cannot infer
 * a single bound type in that case.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AimiPhysioBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideAimiPhysioSource(repo: AIMIPhysioDataRepositoryMTR): AimiPhysioSource = repo

    @Provides
    @SingleIn(AppScope::class)
    fun provideAimiPhysioDataSource(repo: AIMIPhysioDataRepositoryMTR): AimiPhysioDataSource = repo
}
