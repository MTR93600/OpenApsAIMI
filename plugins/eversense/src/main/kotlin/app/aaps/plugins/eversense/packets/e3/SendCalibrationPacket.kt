package app.aaps.plugins.eversense.packets.e3

import app.aaps.plugins.eversense.enums.EversenseSecurityType
import app.aaps.plugins.eversense.packets.EversenseBasePacket
import app.aaps.plugins.eversense.packets.EversensePacket
import app.aaps.plugins.eversense.packets.e3.util.EversenseE3Writer

/**
 * Sends a blood glucose calibration value to the Eversense E3 transmitter.
 *
 * Packet structure verified against official Eversense app
 * (decompiled from operationToSendBloodGlucoseValueWithTwoTimestampsToTransmitter):
 *
 * [0]    = 0x3C (60) — command ID for E3 two-timestamp calibration
 * [1-2]  = sampleDate — FAT-encoded date of the BG measurement
 * [3-4]  = sampleTime — FAT-encoded time of the BG measurement
 * [5-6]  = currentDate — FAT-encoded date of submission (NOW)
 * [7-8]  = currentTime — FAT-encoded time of submission (NOW)
 * [9]    = glucose LSB (data16Bits low byte)
 * [10]   = glucose MSB (data16Bits high byte)
 * [11-13]= 0x00 — additional params
 * [14]   = 0x55 — calibration flag (EversenseKit PR#35)
 * [15-16]= CRC16, appended by buildRequest()
 *
 * NOTE: The previous implementation used command 0x15 (single timestamp, 365-style)
 * which caused the E3 transmitter to read currentTime bytes as the glucose value,
 * producing wildly incorrect readings (e.g. 36416 mg/dL = FAT time 17:50 UTC).
 *
 * @param glucoseMgDl  Blood glucose value in mg/dL
 * @param sampleTimeMs Timestamp of the BG measurement (defaults to now)
 */
@EversensePacket(
    requestId = EversenseE3Packets.SendBloodGlucoseDataWithTwoTimestampsCommandId,
    responseId = EversenseE3Packets.SendBloodGlucoseDataWithTwoTimestampsResponseId,
    typeId = 0,
    securityType = EversenseSecurityType.None
)
class SendCalibrationPacket(
    private val glucoseMgDl: Int,
    private val sampleTimeMs: Long = System.currentTimeMillis()
) : EversenseBasePacket() {

    override fun getRequestData(): ByteArray {
        val now = System.currentTimeMillis()

        val sampleDate = EversenseE3Writer.writeDate(sampleTimeMs)
        val sampleTime = EversenseE3Writer.writeTime(sampleTimeMs)
        val currentDate = EversenseE3Writer.writeDate(now)
        val currentTime = EversenseE3Writer.writeTime(now)

        val bgLsb = (glucoseMgDl and 0xFF).toByte()
        val bgMsb = ((glucoseMgDl shr 8) and 0xFF).toByte()

        return byteArrayOf(
            sampleDate[0], sampleDate[1],
            sampleTime[0], sampleTime[1],
            currentDate[0], currentDate[1],
            currentTime[0], currentTime[1],
            bgLsb,
            bgMsb,
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x55.toByte()
        )
    }

    override fun parseResponse(): Response? {
        if (receivedData.isEmpty()) return null
        return Response()
    }

    class Response : EversenseBasePacket.Response()
}
