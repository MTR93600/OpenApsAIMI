package app.aaps.pump.apex.interfaces

import app.aaps.pump.apex.connectivity.commands.pump.Version

interface ApexDeviceInfo {
    var serialNumber: String
    val version: Version?
}
