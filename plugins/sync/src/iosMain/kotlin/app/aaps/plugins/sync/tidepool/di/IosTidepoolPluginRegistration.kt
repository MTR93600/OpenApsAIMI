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
 * Puts [TidepoolPlugin] into the iOS plugin list, with the same key as on Android.
 *
 * In the `@NotNSClient` bucket, as on Android: `IosAppStartup` merges it only when the app is not a
 * follower, so the master app offers Tidepool and the follower app (AAPSClient) does not.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object IosTidepoolPluginRegistration {

    @Provides
    @IntoMap
    @NotNSClient
    @IntKey(320)
    fun tidepoolEntry(plugin: TidepoolPlugin): PluginBase = plugin
}
