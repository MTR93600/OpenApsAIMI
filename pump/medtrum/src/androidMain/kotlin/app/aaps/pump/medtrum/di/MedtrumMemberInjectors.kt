package app.aaps.pump.medtrum.di

import app.aaps.core.interfaces.di.FeatureMemberInjectors
import app.aaps.pump.medtrum.services.MedtrumService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provides

/**
 * Member injector for the Medtrum Android service.
 *
 * Android builds the service itself, so it fills its own fields from this map afterwards.
 * [FeatureMemberInjectors] keeps the entry out of the map each graph extension declares - without
 * it they are the same multibinding and turn up everywhere.
 *
 * This file used to hold 25 more entries, one per protocol packet, because each packet injected its
 * own logger and its own copy of the pump state. The packets are shared code now and are handed
 * what they need, so only the service is left. `PumpEntryPointInjectorsTest` is what says this file
 * still has to exist: it checks that every Android entry point has an injector, and deleting the
 * whole file took the service with it.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object MedtrumMemberInjectors {

    @Provides
    @FeatureMemberInjectors
    @IntoMap
    @ClassKey(MedtrumService::class)
    fun bindMedtrumService(injector: MembersInjector<MedtrumService>): MembersInjector<*> = injector
}
