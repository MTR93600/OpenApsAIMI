package app.aaps.plugins.dexcomoneplus.parse

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * What a Dexcom ONE+ really answers to a calibration.
 *
 * The 0x35 reply xDrip documents comes from G5 / G6. The only public captures of a ONE+ being
 * calibrated (xDrip discussion #4034) show four bytes echoing the request opcode instead, with no
 * CRC this family's trailer can check. Before this was handled the parser returned null on them, so
 * the user was told the sensor had not answered when in fact it had.
 *
 * An echo is read, reported and logged — but never counted as an acceptance. Nothing published says
 * what its third byte means, and the two captured values point opposite ways.
 */
class OnePlusCalibrateEchoTest {

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    @Test
    fun `the ONE+ echo is decoded instead of being dropped`() {
        val parsed = OnePlusCalibrateRx.parse(bytes(0x34, 0x00, 0x01, 0x00))!!

        assertThat(parsed.valid).isTrue()
        assertThat(parsed.form).isEqualTo(OnePlusCalibrateRx.Form.Echo)
        assertThat(parsed.rawHex).isEqualTo("34000100")
    }

    @Test
    fun `an echo is never reported as an acceptance`() {
        // 0x01 would read as "Code 1" in the 0x35 table and 0x00 as "OK". Neither table applies
        // here, and a calibration the sensor keeps cannot be taken back, so we claim nothing.
        for (statusByte in listOf(0x00, 0x01, 0x02)) {
            val parsed = OnePlusCalibrateRx.parse(bytes(0x34, 0x00, statusByte, 0x00))!!

            assertThat(parsed.accepted()).isFalse()
            assertThat(parsed.wantsSecondCalibration()).isFalse()
            assertThat(parsed.outcome()).isEqualTo(OnePlusCalibrateRx.Outcome.UNKNOWN)
        }
    }

    @Test
    fun `an echo carries its raw bytes into the message, so a field report has them`() {
        val parsed = OnePlusCalibrateRx.parse(bytes(0x34, 0x00, 0x02, 0x00))!!

        assertThat(parsed.message()).contains("34000200")
    }

    @Test
    fun `the G5 style answer still decodes and still says yes or no`() {
        val ok = ByteArray(5).also {
            it[0] = OnePlusCalibrateRx.OPCODE
            it[1] = 0x00
            it[2] = OnePlusCalibrateRx.OK.toByte()
            val crc = OnePlusFastCrc16.calculate(it, 3)
            it[3] = crc[0]
            it[4] = crc[1]
        }
        val parsed = OnePlusCalibrateRx.parse(ok)!!

        assertThat(parsed.form).isEqualTo(OnePlusCalibrateRx.Form.Status)
        assertThat(parsed.accepted()).isTrue()
        assertThat(parsed.outcome()).isEqualTo(OnePlusCalibrateRx.Outcome.ACCEPTED)
    }

    @Test
    fun `a refusal stays a refusal and not an unknown`() {
        val refused = ByteArray(5).also {
            it[0] = OnePlusCalibrateRx.OPCODE
            it[1] = 0x00
            it[2] = OnePlusCalibrateRx.NOT_READY.toByte()
            val crc = OnePlusFastCrc16.calculate(it, 3)
            it[3] = crc[0]
            it[4] = crc[1]
        }
        val parsed = OnePlusCalibrateRx.parse(refused)!!

        assertThat(parsed.outcome()).isEqualTo(OnePlusCalibrateRx.Outcome.REFUSED)
        assertThat(parsed.message()).isEqualTo("Not ready to calibrate")
    }

    @Test
    fun `a packet that is neither shape is still refused`() {
        // A four byte packet that is not the echo, and a five byte one with a broken CRC.
        assertThat(OnePlusCalibrateRx.parse(bytes(0x4e, 0x00, 0x01, 0x00))).isNull()
        assertThat(OnePlusCalibrateRx.parse(bytes(0x35, 0x00, 0x00, 0xff, 0xff))).isNull()
        assertThat(OnePlusCalibrateRx.parse(bytes(0x34, 0x00, 0x01))).isNull()
    }
}
