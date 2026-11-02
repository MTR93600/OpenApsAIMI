package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.interfaces.ApexDeviceInfo

/** Notify pump about connection, should be sent right after connection established. */
class RequestHeartbeat(
    info: ApexDeviceInfo,
    val period: Int,
) : BaseValueCommand(info) {
    override val minProto = ProtocolVersion.PROTO_4_10

    override val valueId = 0x33
    override val isWrite = true

    override val additionalData: ByteArray
        get() = byteArrayOf(period.toUByte().toByte(), 0x00) // TODO: find out what do these values mean

    override fun toString(): String = "RequestHeartbeat()"
}
