package app.aaps.pump.apex.misc

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexPump
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.keys.ApexStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class ApexDeviceInfoImpl(
    private val preferences: Preferences,
    private val apexPump: ApexPump,
) : ApexDeviceInfo {

    override var serialNumber: String
        get() = preferences.get(ApexStringKey.SerialNumber)
        set(s) {
            preferences.put(ApexStringKey.SerialNumber, s)
        }

    override val version: Version?
        get() = apexPump.firmwareVersion
}
