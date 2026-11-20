package app.aaps.plugins.dexcomoneplus.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The applicator text as a camera may give it.
 *
 * In commonTest so it runs on the iPhone simulator too: the iPhone camera is the reader that can
 * hand over a GS1 Data Matrix without its separators. All values are made up.
 */
class OnePlusGs1ApplicatorScanTextTest {

    private val gs = "\u001d"
    private val gtin = "01" + "00312345678901"
    private val serial = "21" + "123456789012"
    private val expiry = "17" + "270630"
    private val made = "11" + "260101"
    private val pin = "240" + "5678"

    @Test
    fun `text with separators is read as before`() {
        val id = assertNotNull(OnePlusGs1ApplicatorParser.parse(gtin + serial + gs + made + expiry + pin))
        assertEquals("5678", id.pin)
        assertEquals("123456789012", id.serial)
    }

    @Test
    fun `text without separators is still read`() {
        val raw = gtin + serial + made + expiry + pin
        val id = assertNotNull(OnePlusGs1ApplicatorParser.parse(raw))
        assertEquals("5678", id.pin)
        assertEquals("123456789012", id.serial)
        assertEquals("00312345678901", id.gtin)
        // The text is kept as it was read, so the field shows what the camera saw.
        assertEquals(raw, id.rawGs1)
    }

    @Test
    fun `the dates may come in the other order or be missing`() {
        assertEquals("5678", OnePlusGs1ApplicatorParser.parse(gtin + serial + expiry + made + pin)?.pin)
        assertEquals("5678", OnePlusGs1ApplicatorParser.parse(gtin + serial + pin)?.pin)
    }

    @Test
    fun `the Data Matrix symbology identifier is ignored`() {
        val id = assertNotNull(OnePlusGs1ApplicatorParser.parse("]d2" + gtin + serial + made + expiry + pin))
        assertEquals("5678", id.pin)
        assertEquals("123456789012", id.serial)
    }

    @Test
    fun `a text that is not a Dexcom applicator code is not read as one`() {
        // A web address, a code with no PIN, a PIN that is too short: the field must not be filled.
        assertNull(OnePlusGs1ApplicatorParser.parse("https://example.org/qr"))
        assertNull(OnePlusGs1ApplicatorParser.parse(gtin + serial + made + expiry))
        assertNull(OnePlusGs1ApplicatorParser.parse(gtin + serial + made + "240" + "56"))
    }
}
