package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.interfaces.ApexDeviceInfo

/** Set connection profile (?) to notify pump about first connection. */
class SetConnectionProfile(
    info: ApexDeviceInfo,
) : BaseValueCommand(info) {
    override val type = 0x35
    override val valueId = 0x11
    override val isWrite = true

    override val additionalData: ByteArray
        get() = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0)

    override fun toString(): String = "SetConnectionProfile()"
}