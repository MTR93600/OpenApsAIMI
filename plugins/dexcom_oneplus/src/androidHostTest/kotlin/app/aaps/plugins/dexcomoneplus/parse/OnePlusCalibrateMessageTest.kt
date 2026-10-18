package app.aaps.plugins.dexcomoneplus.parse

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The bytes of the calibration exchange, checked against xDrip's `CalibrateTxMessage` /
 * `CalibrateRxMessage`.
 *
 * Nothing here sends anything. A value outside 40..400 produces no packet.
 *
 * Provenance: ref `OnePlusCalibrateMessageTest` at `3dd0ca64772`, introduced by `f61474bb73`.
 */
class OnePlusCalibrateMessageTest {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `the packet is opcode, glucose and dex time, little endian, with a CRC`() {
        val packet = OnePlusCalibrateTx.build(glucoseMgdl = 150, dexTimeSeconds = 0x01020304)!!

        assertThat(packet).hasLength(OnePlusCalibrateTx.PACKET_LENGTH)
        assertThat(packet[0]).isEqualTo(OnePlusCalibrateTx.OPCODE)
        assertThat(hex(packet.copyOfRange(1, 3))).isEqualTo("9600")
        assertThat(hex(packet.copyOfRange(3, 7))).isEqualTo("04030201")
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
        assertThat(OnePlusCalibrateTx.build(glucoseMgdl = 40, dexTimeSeconds = 1000)).isNotNull()
        assertThat(OnePlusCalibrateTx.build(glucoseMgdl = 400, dexTimeSeconds = 1000)).isNotNull()
    }

    @Test
    fun `the field case, sensor 60 and blood 150, builds cleanly`() {
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
        assertThat(OnePlusCalibrateRx.parse(byteArrayOf(0x4e, 0x00, 0x00, 0x00, 0x00))).isNull()
        assertThat(OnePlusCalibrateRx.parse(byteArrayOf(OnePlusCalibrateRx.OPCODE, 0x00, 0x00))).isNull()
        val broken = reply(OnePlusCalibrateRx.OK)
        broken[4] = (broken[4] + 1).toByte()
        assertThat(OnePlusCalibrateRx.parse(broken)).isNull()
    }
}
