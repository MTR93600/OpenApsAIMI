package app.aaps.pump.omnipod.dashctl.comm.exceptions

import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex

class CouldNotParseMessageException(val payload: ByteArray) :
    Exception("Could not parse message payload: ${payload.toCommLogHex()}")
