package app.aaps.plugins.source.di

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.plugins.source.activities.CgmDriverLogActivity
import app.aaps.plugins.source.activities.DexcomOnePlusStartActivity
import app.aaps.plugins.source.activities.DexcomOnePlusStatusActivity
import app.aaps.plugins.source.activities.DexcomOnePlusWarmupActivity
import app.aaps.plugins.source.activities.Libre3StartActivity
import app.aaps.plugins.source.activities.Libre3StatusActivity
import app.aaps.plugins.source.activities.Libre3WarmupActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/**
 * Member injectors for the Dexcom ONE+ / G7 and Libre 3 screens.
 *
 * Android constructs an activity, so it cannot take its dependencies in a constructor.
 * [app.aaps.core.ui.compose.MetroAppCompatActivity] fills the fields from this map.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object SourceMemberInjectors {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(DexcomOnePlusStartActivity::class)
    fun bindDexcomOnePlusStartActivity(
        injector: MembersInjector<DexcomOnePlusStartActivity>
    ): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(DexcomOnePlusStatusActivity::class)
    fun bindDexcomOnePlusStatusActivity(
        injector: MembersInjector<DexcomOnePlusStatusActivity>
    ): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(DexcomOnePlusWarmupActivity::class)
    fun bindDexcomOnePlusWarmupActivity(
        injector: MembersInjector<DexcomOnePlusWarmupActivity>
    ): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(Libre3StartActivity::class)
    fun bindLibre3StartActivity(
        injector: MembersInjector<Libre3StartActivity>
    ): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(Libre3StatusActivity::class)
    fun bindLibre3StatusActivity(
        injector: MembersInjector<Libre3StatusActivity>
    ): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(Libre3WarmupActivity::class)
    fun bindLibre3WarmupActivity(
        injector: MembersInjector<Libre3WarmupActivity>
    ): MembersInjector<*> = injector

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(CgmDriverLogActivity::class)
    fun bindCgmDriverLogActivity(
        injector: MembersInjector<CgmDriverLogActivity>
    ): MembersInjector<*> = injector
}
