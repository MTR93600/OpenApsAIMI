package app.aaps.plugins.sync.nsclientV3.remotecontrol

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginBaseWithPreferences
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.sync.SyncStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntKey
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

/**
 * AIMI Remote Control for the follower phone (AAPSClient): lets a parent send AIMI modes and
 * contexts to the master phone through Nightscout.
 *
 * Only a follower has it, as on dev_OAPSAIMI: it is listed and switched on only when
 * [Config.AAPSCLIENT] is true, and switched off everywhere else. An iOS phone is always a master,
 * so it never shows this plugin. The master side - taking the contexts in - is
 * `NsIncomingDataProcessor` and works on every platform.
 *
 * The dev plugin first asked for a premium licence key that was checked against a hash written in
 * the source code. That check is not ported: a key in public source code is not a secret.
 */
@SingleIn(AppScope::class)
@ContributesIntoMap(AppScope::class, binding = binding<PluginBase>())
@IntKey(315)
@Inject
class RemoteControlPlugin(
    aapsLogger: AAPSLogger,
    rh: TextResolver,
    preferences: Preferences,
    notificationManager: NotificationManager,
    config: Config
) : PluginBaseWithPreferences(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .icon(Icons.Default.Home)
        .pluginName(SyncStrings.aimi_rc_title)
        .description(SyncStrings.aimi_rc_description)
        .showInList { config.AAPSCLIENT }
        .enforceEnabledOnlyWhen { config.AAPSCLIENT }
        .composeContent { RemoteControlComposeContent(title = rh.gs(SyncStrings.aimi_rc_title)) },
    ownPreferences = RemoteControlStringKey.entries,
    aapsLogger, rh, preferences, notificationManager
) {

    /** The PIN and password hash are not exported, so an import would wipe them. They are kept. */
    private var keptValues: Map<RemoteControlStringKey, String> = emptyMap()

    override fun beforeImport() {
        keptValues = RemoteControlStringKey.entries.associateWith { preferences.get(it) }
    }

    override fun afterImport() {
        keptValues.forEach { (key, value) -> if (value.isNotEmpty()) preferences.put(key, value) }
        keptValues = emptyMap()
    }
}
