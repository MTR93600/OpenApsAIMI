package app.aaps.pump.omnipod.dashctl.comm.pair

import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.message.StringLengthPrefixEncoding

/** Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.pair.PairMessage`, unchanged. */
class PairMessage(
    val sequenceNumber: Byte,
    val source: Id,
    val destination: Id,
    private val keys: Array<String>,
    private val payloads: Array<ByteArray>,
    val messagePacket: MessagePacket = MessagePacket(
        type = MessageType.PAIRING,
        source = source,
        destination = destination,
        payload = StringLengthPrefixEncoding.formatKeys(
            keys,
            payloads,
        ),
        sequenceNumber = sequenceNumber,
        sas = true // TODO: understand why this is true for PairMessages
    )
)
