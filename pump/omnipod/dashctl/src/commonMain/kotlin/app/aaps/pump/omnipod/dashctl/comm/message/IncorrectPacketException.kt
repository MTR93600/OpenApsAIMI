package app.aaps.pump.omnipod.dashctl.comm.message

import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex

class IncorrectPacketException(
    val payload: ByteArray,
    expectedIndex: Byte? = null
) : Exception("Invalid payload: ${payload.toCommLogHex()}. Expected index: $expectedIndex")
