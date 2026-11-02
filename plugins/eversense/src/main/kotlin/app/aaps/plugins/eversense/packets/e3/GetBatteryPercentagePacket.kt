package app.aaps.plugins.eversense.packets.e3

import app.aaps.plugins.eversense.enums.BatteryLevel
import app.aaps.plugins.eversense.enums.EversenseE3Memory
import app.aaps.plugins.eversense.enums.EversenseSecurityType
import app.aaps.plugins.eversense.packets.EversenseBasePacket
import app.aaps.plugins.eversense.packets.EversensePacket
import app.aaps.plugins.eversense.util.EversenseLogger

@EversensePacket(
    requestId = EversenseE3Packets.ReadSingleByteSerialFlashRegisterCommandId,
    responseId = EversenseE3Packets.ReadSingleByteSerialFlashRegisterResponseId,
    typeId = 0,
    securityType = EversenseSecurityType.None
)
class GetBatteryPercentagePacket : EversenseBasePacket() {

    override fun getRequestData(): ByteArray {
        return EversenseE3Memory.BatteryPercentage.getRequestData()
    }

    override fun parseResponse(): Response? {
        if (receivedData.isEmpty()) {
            return null
        }

        // The E3 battery register holds an index 0..11, not a percentage. BatteryLevel is the
        // single place that turns the index into a percentage (0 -> 0%, 1 -> 5%, 2 -> 10%,
        // 3 -> 25% ... 11 -> 100%). Do not map it again anywhere else.
        // Any other value means we did not read a battery register, so report -1 (unknown)
        // instead of guessing a value. -1 is handled downstream as "no battery info".
        val raw = receivedData[getStartIndex()].toInt() and 0xFF
        val level = BatteryLevel.from(raw)
        if (level == BatteryLevel.UNKNOWN) {
            EversenseLogger.warning("GetBatteryPercentagePacket", "Battery register value out of range: $raw — reporting unknown")
        }
        return Response(rawLevel = raw, percentage = level.toPercentage())
    }

    data class Response(val rawLevel: Int, val percentage: Int) : EversenseBasePacket.Response()
}