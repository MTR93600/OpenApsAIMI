package app.aaps.pump.omnipod.dashctl.comm.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Android has no test for BleCommand. The expected bytes come from the Android source
 * `pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/command/BleCommand.kt`.
 */
class BleCommandTest {

    @Test
    fun `simple commands are one type byte`() {
        assertEquals("00", BleCommandRTS.data.hex())
        assertEquals("01", BleCommandCTS.data.hex())
        assertEquals("03", BleCommandAbort.data.hex())
        assertEquals("04", BleCommandSuccess.data.hex())
        assertEquals("05", BleCommandFail.data.hex())
        assertEquals("09", BleCommandIncorrect("x", byteArrayOf()).data.hex())
    }

    @Test
    fun `NACK is type byte then index`() {
        assertEquals("0203", BleCommandNack(3).data.hex())
        assertEquals("02ff", BleCommandNack(-1).data.hex())
    }

    @Test
    fun `HELLO is 06 01 04 then the controller id big endian`() {
        // Android: ByteBuffer.allocate(6).put(1).put(4).putInt(controllerId), after the type byte.
        // 4242 is OmnipodDashBleManagerImpl.CONTROLLER_ID.
        assertEquals("06010400001092", BleCommandHello(4242).data.hex())
        assertEquals("060104fffffffe", BleCommandHello(-2).data.hex())
    }

    @Test
    fun `parse gives back each command`() {
        assertSame(BleCommandRTS, BleCommand.parse(byteArrayOf(0x00)))
        assertSame(BleCommandCTS, BleCommand.parse(byteArrayOf(0x01)))
        assertSame(BleCommandAbort, BleCommand.parse(byteArrayOf(0x03)))
        assertSame(BleCommandSuccess, BleCommand.parse(byteArrayOf(0x04)))
        assertSame(BleCommandFail, BleCommand.parse(byteArrayOf(0x05)))
        assertEquals(BleCommandNack(7), BleCommand.parse(byteArrayOf(0x02, 0x07)))
        // Extra bytes after a one-byte command are ignored, as on Android.
        assertSame(BleCommandSuccess, BleCommand.parse(byteArrayOf(0x04, 0x11)))
    }

    @Test
    fun `parse returns Incorrect for bad input`() {
        val empty = assertIs<BleCommandIncorrect>(BleCommand.parse(byteArrayOf()))
        assertEquals("Incorrect command: empty payload", empty.msg)

        val shortNack = assertIs<BleCommandIncorrect>(BleCommand.parse(byteArrayOf(0x02)))
        assertEquals("Incorrect NACK payload", shortNack.msg)

        val hello = assertIs<BleCommandIncorrect>(BleCommand.parse(byteArrayOf(0x06, 0x01)))
        assertEquals("Incorrect hello command received", hello.msg)

        val incorrect = assertIs<BleCommandIncorrect>(BleCommand.parse(byteArrayOf(0x09)))
        assertEquals("Incorrect command received", incorrect.msg)

        // 0x07 is not a BleCommandType: byValue throws IllegalArgumentException, parse catches it.
        val unknown = assertIs<BleCommandIncorrect>(BleCommand.parse(byteArrayOf(0x07)))
        assertEquals("Incorrect command payload", unknown.msg)
    }

    @Test
    fun `NACK parse checks the header`() {
        val wrongHeader = assertIs<BleCommandIncorrect>(BleCommandNack.parse(byteArrayOf(0x04, 0x01)))
        assertEquals("Incorrect NACK header", wrongHeader.msg)
    }

    @Test
    fun `commands with the same bytes are equal`() {
        assertEquals<BleCommand>(BleCommandNack(1), BleCommandNack(1))
        assertEquals(BleCommandNack(1).hashCode(), BleCommandNack(1).hashCode())
        // The objects use the toString of BleCommand (data classes make their own).
        assertEquals("Raw command: [00]", BleCommandRTS.toString())
        assertEquals("Raw command: [05]", BleCommandFail.toString())
    }

    @Test
    fun `BleCommandType byValue throws for an unknown value`() {
        assertEquals(BleCommandType.NACK, BleCommandType.byValue(0x02))
        assertFailsWith<IllegalArgumentException> { BleCommandType.byValue(0x08) }
    }
}

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
