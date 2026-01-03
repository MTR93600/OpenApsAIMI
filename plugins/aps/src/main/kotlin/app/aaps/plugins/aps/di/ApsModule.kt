package app.aaps.plugins.aps.di

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.autotune.Autotune
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.autotune.AutotunePlugin
import app.aaps.plugins.aps.loop.LoopPlugin
import app.aaps.plugins.aps.openAPSAIMI.di.WCycleModule
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiProfileAdvisorActivity
import app.aaps.plugins.aps.openAPSAIMI.context.ui.ContextActivity
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module(
    includes = [
        AutotuneModule::class,
        LoopModule::class,
        WCycleModule::class,
        ApsModule.Bindings::class
    ]
)

@Suppress("unused")
abstract class ApsModule {

    @ContributesAndroidInjector abstract fun contributesOpenAPSFragment(): OpenAPSFragment
    @ContributesAndroidInjector abstract fun contributesAimiProfileAdvisorActivity(): AimiProfileAdvisorActivity
    @ContributesAndroidInjector abstract fun contributesAimiModeSettingsActivity(): app.aaps.plugins.aps.openAPSAIMI.advisor.AimiModeSettingsActivity
    @ContributesAndroidInjector abstract fun contributesMealAdvisorActivity(): app.aaps.plugins.aps.openAPSAIMI.advisor.meal.MealAdvisorActivity
    @ContributesAndroidInjector abstract fun contributesContextActivity(): ContextActivity

    @Module
    interface Bindings {

        @Binds fun bindLoop(loopPlugin: LoopPlugin): Loop
        @Binds fun bindAutotune(autotunePlugin: AutotunePlugin): Autotune
    }
}