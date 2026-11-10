package app.aaps.plugins.aps.di

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.plugins.aps.openAPSAIMI.physio.AimiHealthConnectPermissionActivity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/**
 * Member injectors for the AIMI Activities.
 *
 * Android constructs an activity, so it cannot take its dependencies in a constructor.
 * [app.aaps.core.ui.compose.MetroAppCompatActivity] fills the fields from this map.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AimiMemberInjectors {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(AimiHealthConnectPermissionActivity::class)
    fun bindAimiHealthConnectPermissionActivity(
        injector: MembersInjector<AimiHealthConnectPermissionActivity>
    ): MembersInjector<*> = injector
}
