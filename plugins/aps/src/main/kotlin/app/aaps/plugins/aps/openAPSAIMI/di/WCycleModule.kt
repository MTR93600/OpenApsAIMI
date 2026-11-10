package app.aaps.plugins.aps.openAPSAIMI.di

import android.content.Context
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleAdjuster
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleCsvLogger
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleEstimator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WCycleModule {

    @Provides
    @Singleton
    fun provideWCyclePreferences(preferences: Preferences): WCyclePreferences =
        WCyclePreferences(preferences)

    @Provides
    @Singleton
    fun provideWCycleEstimator(preferences: WCyclePreferences): WCycleEstimator =
        WCycleEstimator(preferences)


    @Provides
    @Singleton
    fun provideWCycleLearner(context: Context, storageHelper: AimiStorageHelper): WCycleLearner =
        WCycleLearner(ctx = context, storageHelper = storageHelper)

    @Provides
    @Singleton
    fun provideWCycleAdjuster(
        preferences: WCyclePreferences,
        estimator: WCycleEstimator,
        learner: WCycleLearner
    ): WCycleAdjuster =
        WCycleAdjuster(preferences, estimator, learner)

    @Provides
    @Singleton
    fun provideWCycleCsvLogger(context: Context, storageHelper: AimiStorageHelper): WCycleCsvLogger =
        WCycleCsvLogger(context, storageHelper)

    @Provides
    @Singleton
    fun provideWCycleFacade(
        adjuster: WCycleAdjuster,
        logger: WCycleCsvLogger
    ): WCycleFacade =
        WCycleFacade(adjuster, logger)
}
