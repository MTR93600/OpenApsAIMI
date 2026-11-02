package app.aaps.pump.apex.di

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.pump.apex.ApexService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/**
 * Member injector for [ApexService] - the `@ContributesAndroidInjector` replacement, as in the other
 * pump drivers. It only reaches the graph when `:pump:apex` is a dependency of the app, which today
 * it is not (see `pumpExclusions` in app/build.gradle.kts).
 */
@ContributesTo(AppScope::class)
@BindingContainer
object ApexMemberInjectors {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(ApexService::class)
    fun bindApexService(injector: MembersInjector<ApexService>): MembersInjector<*> = injector
}
