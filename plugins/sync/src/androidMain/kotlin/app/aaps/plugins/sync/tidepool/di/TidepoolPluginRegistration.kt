package app.aaps.plugins.sync.tidepool.di

import app.aaps.core.interfaces.di.NotNSClient
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.plugins.sync.tidepool.TidepoolPlugin
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides

/**
 * Puts [TidepoolPlugin] into the Android plugin list.
 *
 * The qualifier is not decoration: `MetroGraphs.allPlugins` merges the `@NotNSClient` bucket only when
 * the build is not an AAPSClient one, which is where Tidepool has always been. The plugin itself is
 * shared code; `@NotNSClient` is an Android-only qualifier, so the registration lives here.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object TidepoolPluginRegistration {

    @Provides
    @IntoMap
    @IntKey(320)
    @NotNSClient
    fun tidepoolEntry(plugin: TidepoolPlugin): PluginBase = plugin
}
