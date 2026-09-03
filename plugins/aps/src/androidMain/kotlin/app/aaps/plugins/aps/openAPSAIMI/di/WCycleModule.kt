package app.aaps.plugins.aps.openAPSAIMI.di

import android.content.Context
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleAdjuster
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleEstimator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object WCycleModule {

    @Provides
    @SingleIn(AppScope::class)
    fun provideWCyclePreferences(preferences: Preferences): WCyclePreferences =
        WCyclePreferences(preferences)

    @Provides
    @SingleIn(AppScope::class)
    fun provideWCycleEstimator(preferences: WCyclePreferences): WCycleEstimator =
        WCycleEstimator(preferences)

    @Provides
    @SingleIn(AppScope::class)
    fun provideWCycleLearner(context: Context, storageHelper: AimiStorageHelper): WCycleLearner =
        WCycleLearner(ctx = context, storageHelper = storageHelper)

    @Provides
    @SingleIn(AppScope::class)
    fun provideWCycleAdjuster(
        preferences: WCyclePreferences,
        estimator: WCycleEstimator,
        learner: WCycleLearner
    ): WCycleAdjuster =
        WCycleAdjuster(preferences, estimator, learner)

    @Provides
    @SingleIn(AppScope::class)
    fun provideWCycleCsvLogger(context: Context, storageHelper: AimiStorageHelper): WCycleCsvLogger =
        WCycleCsvLogger(context, storageHelper)

    @Provides
    @SingleIn(AppScope::class)
    fun provideWCycleFacade(
        adjuster: WCycleAdjuster,
        logger: WCycleCsvLogger
    ): WCycleFacade =
        WCycleFacade(adjuster, logger)
}
