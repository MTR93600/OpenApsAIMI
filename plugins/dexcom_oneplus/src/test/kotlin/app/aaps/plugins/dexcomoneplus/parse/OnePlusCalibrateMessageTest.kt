package app.aaps.plugins.dexcomoneplus.parse

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The bytes of the calibration exchange, checked against xDrip's `CalibrateTxMessage` /
 * `CalibrateRxMessage`, which is the only description of this command we have.
 *
 * Nothing here sends anything — see docs/DEXCOM_ONEPLUS_CALIBRATION_TO_SENSOR.md for why the path
 * stays switched off until a Dexcom ONE+ has been seen to answer.
 */
class OnePlusCalibrateMessageTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `the packet is opcode, glucose and dex time, little endian, with a CRC`() {
        val packet = OnePlusCalibrateTx.build(glucoseMgdl = 150, dexTimeSeconds = 0x01020304)!!

        assertThat(packet).hasLength(OnePlusCalibrateTx.PACKET_LENGTH)
        assertThat(packet[0]).isEqualTo(OnePlusCalibrateTx.OPCODE)
        // 150 = 0x0096 -> 96 00 little endian
        assertThat(hex(packet.copyOfRange(1, 3))).isEqualTo("9600")
        // 0x01020304 -> 04 03 02 01 little endian
        assertThat(hex(packet.copyOfRange(3, 7))).isEqualTo("04030201")
        // The CRC covers everything before it, and the packet checks out as a whole.
        val expected = OnePlusFastCrc16.calculate(packet, OnePlusCalibrateTx.PACKET_LENGTH - 2)
        assertThat(packet[7]).isEqualTo(expected[0])
        assertThat(packet[8]).isEqualTo(expected[1])
        assertThat(OnePlusFastCrc16.check(packet)).isTrue()
    }

    @Test
    fun `a value the sensor would refuse is never built`() {
        assertThat(OnePlusCalibrateTx.build(glucoseMgdl = 39, dexTimeSeconds = 1000)).isNull()
        assertThat(OnePlusCalibrateTx.build(glucoseMgdl = 401, dexTimeSeconds = 1000)).isNull()
        assertThat(OnePlusCalibrateTx.build(glucoseMgdl = 150, dexTimeSeconds = -1)).isNull()
        // The ends of the range are allowed.
        assertThat(OnePlusCalibrateTx.build(glucoseMgdl = 40, dexTimeSeconds = 1000)).isNotNull()
        assertThat(OnePlusCalibrateTx.build(glucoseMgdl = 400, dexTimeSeconds = 1000)).isNotNull()
    }

    @Test
    fun `the field case, sensor 60 and blood 150, builds cleanly`() {
        // 2026-09: the sensor read 60 while the blood was 150. The official app took it, and the
        // size of the gap is not something this packet has any opinion about.
        val packet = OnePlusCalibrateTx.build(glucoseMgdl = 150, dexTimeSeconds = 123_456)

        assertThat(packet).isNotNull()
        assertThat(OnePlusFastCrc16.check(packet!!)).isTrue()
    }

    private fun reply(result: Int): ByteArray {
        val packet = byteArrayOf(OnePlusCalibrateRx.OPCODE, 0x00, result.toByte(), 0, 0)
        val crc = OnePlusFastCrc16.calculate(packet, OnePlusCalibrateRx.PACKET_LENGTH - 2)
        packet[3] = crc[0]
        packet[4] = crc[1]
        return packet
    }

    @Test
    fun `the three accepted answers are read as accepted`() {
        assertThat(OnePlusCalibrateRx.parse(reply(OnePlusCalibrateRx.OK))!!.accepted()).isTrue()
        assertThat(OnePlusCalibrateRx.parse(reply(OnePlusCalibrateRx.DUPLICATE))!!.accepted()).isTrue()
        val second = OnePlusCalibrateRx.parse(reply(OnePlusCalibrateRx.SECOND_NEEDED))!!
        assertThat(second.accepted()).isTrue()
        assertThat(second.wantsSecondCalibration()).isTrue()
    }

    @Test
    fun `a refusal is a refusal, and says why`() {
        val rejected = OnePlusCalibrateRx.parse(reply(OnePlusCalibrateRx.REJECTED))!!
        assertThat(rejected.accepted()).isFalse()
        assertThat(rejected.message()).isEqualTo("Rejected")
        assertThat(OnePlusCalibrateRx.parse(reply(OnePlusCalibrateRx.NOT_READY))!!.message())
            .isEqualTo("Not ready to calibrate")
        assertThat(OnePlusCalibrateRx.parse(reply(OnePlusCalibrateRx.STOPPED))!!.accepted()).isFalse()
    }

    @Test
    fun `a packet that is not a calibration answer is not read as one`() {
        // Wrong opcode, wrong length, and a broken CRC must all be refused rather than guessed at:
        // a wrong read here would tell the user their calibration was taken when it was not.
        assertThat(OnePlusCalibrateRx.parse(byteArrayOf(0x4e, 0x00, 0x00, 0x00, 0x00))).isNull()
        assertThat(OnePlusCalibrateRx.parse(byteArrayOf(OnePlusCalibrateRx.OPCODE, 0x00, 0x00))).isNull()
        val broken = reply(OnePlusCalibrateRx.OK)
        broken[4] = (broken[4] + 1).toByte()
        assertThat(OnePlusCalibrateRx.parse(broken)).isNull()
    }
}
