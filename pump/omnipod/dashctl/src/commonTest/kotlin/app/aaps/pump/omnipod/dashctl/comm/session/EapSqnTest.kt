package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android has no test for EapSqn. The expected bytes are what `java.nio.ByteBuffer` gives. */
class EapSqnTest {

    @Test
    fun `a long is written as six big endian bytes`() {
        assertEquals("000000000001", EapSqn(1).value.toParityHex())
        assertEquals("00000000015e", EapSqn(0x15e).value.toParityHex())
        assertEquals("0000ffffffff", EapSqn(0xffffffffL).value.toParityHex())
        assertEquals("ffffffffffff", EapSqn(0xffffffffffffL).value.toParityHex())
    }

    @Test
    fun `six bytes are read as an unsigned big endian number`() {
        assertEquals(0x15dL, EapSqn(parityHex("00000000015d")).toLong())
        assertEquals(0xffffffffffffL, EapSqn(parityHex("ffffffffffff")).toLong())
        assertEquals(0x80ff00000000L, EapSqn(parityHex("80ff00000000")).toLong())
    }

    @Test
    fun `increment carries into the next byte`() {
        assertEquals("00000000015e", EapSqn(parityHex("00000000015d")).increment().value.toParityHex())
        assertEquals("000000000100", EapSqn(parityHex("0000000000ff")).increment().value.toParityHex())
        assertEquals("EapSqn(value=350)", EapSqn(350).toString())
    }
}
