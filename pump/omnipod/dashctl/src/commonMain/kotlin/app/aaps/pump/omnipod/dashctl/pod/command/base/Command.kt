package app.aaps.pump.omnipod.dashctl.pod.command.base

import app.aaps.pump.omnipod.dashctl.pod.definition.Encodable

interface Command : Encodable {

    val commandType: CommandType
    val sequenceNumber: Short
}
