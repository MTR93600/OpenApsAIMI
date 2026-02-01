package app.aaps.pump.apex.misc

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.ApexPump
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.keys.ApexStringKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApexDeviceInfoImpl @Inject constructor(
    private val preferences: Preferences,
    private val apexPump: ApexPump,
): ApexDeviceInfo {
    override var serialNumber: String
        get() = preferences.get(ApexStringKey.SerialNumber) ?: ""
        set(s) { preferences.put(ApexStringKey.SerialNumber, s) }

    override val version: Version?
        get() = apexPump.firmwareVersion
}