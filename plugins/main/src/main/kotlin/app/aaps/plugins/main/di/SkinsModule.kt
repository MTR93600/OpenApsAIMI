package app.aaps.plugins.main.di

import app.aaps.core.interfaces.skin.SkinDescriptionProvider
import app.aaps.plugins.main.skins.SkinButtonsOn
import app.aaps.plugins.main.skins.SkinClassic
import app.aaps.plugins.main.skins.SkinGlass
import app.aaps.plugins.main.skins.SkinInterface
import app.aaps.plugins.main.skins.SkinLargeDisplay
import app.aaps.plugins.main.skins.SkinLowRes
import app.aaps.plugins.main.skins.SkinMinimal
import app.aaps.plugins.main.skins.SkinProvider
import app.aaps.plugins.main.skins.SkinProviderImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntKey
import dagger.multibindings.IntoMap
import javax.inject.Qualifier

/**
 * Overview skins for graph heights and legacy [OverviewFragmentBinding] layout tweaks.
 * [SkinMinimal] (key 0) keeps dashboard-oriented Compose home as the default.
 */
@Module(includes = [SkinsModule.Bindings::class])
@InstallIn(SingletonComponent::class)
open class SkinsModule {

    @Module
    @InstallIn(SingletonComponent::class)
    interface Bindings {
        @Binds
        fun bindSkinProvider(impl: SkinProviderImpl): SkinProvider

        @Binds
        fun bindSkinDescriptionProvider(impl: SkinProviderImpl): SkinDescriptionProvider
    }

    @Provides
    @Skin
    @IntoMap
    @IntKey(0)
    fun bindsSkinMinimal(skinMinimal: SkinMinimal): SkinInterface = skinMinimal

    @Provides
    @Skin
    @IntoMap
    @IntKey(6)
    fun bindsSkinGlass(skinGlass: SkinGlass): SkinInterface = skinGlass

    @Provides
    @Skin
    @IntoMap
    @IntKey(10)
    fun bindsSkinClassic(skinClassic: SkinClassic): SkinInterface = skinClassic

    @Provides
    @Skin
    @IntoMap
    @IntKey(20)
    fun bindsSkinButtonsOn(skinButtonsOn: SkinButtonsOn): SkinInterface = skinButtonsOn

    @Provides
    @Skin
    @IntoMap
    @IntKey(30)
    fun bindsSkinLargeDisplay(skinLargeDisplay: SkinLargeDisplay): SkinInterface = skinLargeDisplay

    @Provides
    @Skin
    @IntoMap
    @IntKey(40)
    fun bindsSkinLowRes(skinLowRes: SkinLowRes): SkinInterface = skinLowRes

    @Qualifier
    annotation class Skin
}
