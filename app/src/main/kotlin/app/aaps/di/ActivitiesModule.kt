package app.aaps.di

import app.aaps.activities.ComparatorActivity
import app.aaps.activities.DashboardPreviewActivity
import dagger.Module
import dagger.android.ContributesAndroidInjector
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class ActivitiesModule {

    @ContributesAndroidInjector abstract fun contributesDashboardPreviewActivity(): DashboardPreviewActivity
    @ContributesAndroidInjector abstract fun contributesComparatorActivity(): ComparatorActivity
}
