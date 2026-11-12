package app.aaps.pump.omnipod.dashctl.comm.message

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of the Android `StringLengthPrefixEncodingTest`, same test vectors. */
class StringLengthPrefixEncodingTest {

    private val p0Payload = parityHex("50,30,3d,00,01,a5".replace(",", "")) // from logs
    private val p0Content = parityHex("a5")

    @Test fun testFormatKeysP0() {
        val payload = StringLengthPrefixEncoding.formatKeys(arrayOf("P0="), arrayOf(p0Content))
        assertEquals(payload.toParityHex(), p0Payload.toParityHex())
    }

    @Test fun testParseKeysP0() {
        val parsed = StringLengthPrefixEncoding.parseKeys(arrayOf("P0="), p0Payload)
        assertEquals(1, parsed.size)
        assertEquals(p0Content.toParityHex(), parsed[0].toParityHex())
    }
}
