package app.aaps.plugins.sync.nsShared

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.sync.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote Control Plugin for NSClient Parent App.
 * Provides secure interface for parents to send AIMI commands remotely.
 */
@Singleton
class RemoteControlPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(RemoteControlFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_home)
        .pluginName(R.string.remote_control_title)
        .shortName(R.string.remote_control_title)
        .neverVisible(false)
        .showInList { true }
        .alwaysEnabled(true)
        .description(R.string.remote_control_description),
    aapsLogger, rh
)
