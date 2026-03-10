package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.apex.R
import app.aaps.pump.apex.connectivity.ProtocolVersion

class Version: PumpObjectModel {
    private var _fwMajor: Int? = null
    private var _fwMinor: Int? = null
    private var _proto: ProtocolVersion? = null
    private var command: PumpCommand? = null

    constructor(command: PumpCommand) {
        _fwMajor = null
        _fwMinor = null
        _proto = null
        this.command = command
    }

    constructor(fwMajor: Int, fwMinor: Int, protocolVersion: ProtocolVersion) {
        _fwMajor = fwMajor
        _fwMinor = fwMinor
        _proto = protocolVersion
        command = null
    }

    /** Firmware major part of version */
    val firmwareMajor get() = _fwMajor ?: command!!.objectData[6].toUByte().toInt()

    /** Firmware minor part of version */
    val firmwareMinor get() = _fwMinor ?: command!!.objectData[7].toUByte().toInt()

    /** Protocol major part of version */
    val protocolMajor get() = _proto?.major ?: command!!.objectData[8].toUByte().toInt()

    /** Protocol minor part of version */
    val protocolMinor get() = _proto?.minor ?: command!!.objectData[9].toUByte().toInt()

    fun toLocalString(rh: ResourceHelper): String {
        return rh.gs(R.string.overview_pump_fw, firmwareMajor, firmwareMinor, protocolMajor, protocolMinor)
    }

    fun atleastProto(proto: ProtocolVersion): Boolean {
        return protocolMajor >= proto.major && protocolMinor >= proto.minor
    }

    fun isSupported(min: ProtocolVersion, max: ProtocolVersion): Boolean {
        if (min.major > protocolMajor || max.major < protocolMajor) return false
        if (max.major > protocolMajor) return true
        if (max.minor < protocolMinor) return false
        return true
    }

    override fun toString(): String = "Version(fw = $firmwareMajor.$firmwareMinor, proto = $protocolMajor.$protocolMinor)"
}
