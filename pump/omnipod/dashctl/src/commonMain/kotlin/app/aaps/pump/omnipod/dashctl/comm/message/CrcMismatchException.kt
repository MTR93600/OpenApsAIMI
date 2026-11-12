package app.aaps.pump.omnipod.dashctl.comm.message

import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex

class CrcMismatchException(val expected: Long, actual: Long, val payload: ByteArray) :
    Exception("CRC mismatch. Actual: $actual. Expected: $expected. Payload: ${payload.toCommLogHex()}")
