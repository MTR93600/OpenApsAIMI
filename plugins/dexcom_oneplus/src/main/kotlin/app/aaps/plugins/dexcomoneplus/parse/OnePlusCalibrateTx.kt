package app.aaps.plugins.dexcomoneplus.parse

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control Calibrate request (opcode 0x34) — hands a fingerstick to the sensor's own algorithm.
 *
 * Layout (9 bytes incl. CRC): opcode | glucose(u16 LE, mg/dL) | dexTime(u32 LE) | crc16
 *
 * The time is the moment the BLOOD was measured, in transmitter seconds, not the moment the value is
 * sent: the sensor needs it to line the fingerstick up with its own history. A value whose dex time
 * is wrong is worse than no calibration at all.
 *
 * ⚠️ Nothing here decides WHETHER to send. The sensor keeps a calibration for good — it cannot be
 * edited or deleted afterwards — so the gates live at the call site (range, age, stability), and the
 * whole path is behind an engineering switch that is off by default. See
 * docs/DEXCOM_ONEPLUS_CALIBRATION_TO_SENSOR.md.
 *
 * Provenance: xDrip `CalibrateTxMessage` (GPL-3.0), whose G7 path sends this same opcode.
 */
object OnePlusCalibrateTx {

    const val OPCODE: Byte = 0x34
    const val PACKET_LENGTH: Int = 9

    /** Range the sensor itself accepts, and the same one the official app and xDrip apply. */
    const val MIN_GLUCOSE_MGDL = 40
    const val MAX_GLUCOSE_MGDL = 400

    /**
     * @param glucoseMgdl fingerstick value in mg/dL, [MIN_GLUCOSE_MGDL]..[MAX_GLUCOSE_MGDL]
     * @param dexTimeSeconds transmitter time of the FINGERSTICK (not of this call)
     * @return the packet, or null when the value is outside the range the sensor accepts
     */
    fun build(glucoseMgdl: Int, dexTimeSeconds: Int): ByteArray? {
        if (glucoseMgdl < MIN_GLUCOSE_MGDL || glucoseMgdl > MAX_GLUCOSE_MGDL) return null
        if (dexTimeSeconds < 0) return null
        val buf = ByteBuffer.allocate(PACKET_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(OPCODE)
        buf.putShort(glucoseMgdl.toShort())
        buf.putInt(dexTimeSeconds)
        val packet = buf.array()
        val crc = OnePlusFastCrc16.calculate(packet, PACKET_LENGTH - 2)
        packet[PACKET_LENGTH - 2] = crc[0]
        packet[PACKET_LENGTH - 1] = crc[1]
        return packet
    }
}
