package app.aaps.pump.medtrum.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The serial derivation both platforms share.
 *
 * It runs on every target on purpose: Android and iOS must read the very same number out of the
 * very same pump name. A pump reached with the wrong serial never connects.
 */
class MedtrumDeviceNameTest {

    @Test
    fun readsTheSerialOutOfAPumpName() {
        assertEquals(0x2F4A1BL, MedtrumDeviceName.serialFromName("MT-2F4A1B"))
        // The stored text is uppercase but a lowercase name has to give the same number.
        assertEquals(0x2F4A1BL, MedtrumDeviceName.serialFromName("MT-2f4a1b"))
        assertEquals(12345L, MedtrumDeviceName.serialFromName("MT-3039"))
    }

    @Test
    fun refusesANameThatIsNotAPump() {
        assertNull(MedtrumDeviceName.serialFromName(null))
        assertNull(MedtrumDeviceName.serialFromName(""))
        // Android filters its scan on the name "MT". That bare name carries no serial.
        assertNull(MedtrumDeviceName.serialFromName("MT"))
        assertNull(MedtrumDeviceName.serialFromName("Dexcom ONE+"))
        assertNull(MedtrumDeviceName.serialFromName("mt-2F4A1B"))
        assertNull(MedtrumDeviceName.serialFromName("XMT-2F4A1B"))
    }

    @Test
    fun refusesAnEmptyOrBrokenSerial() {
        assertNull(MedtrumDeviceName.serialFromName("MT-"))
        assertNull(MedtrumDeviceName.serialFromName("MT-ZZZZ"))
        assertNull(MedtrumDeviceName.serialFromName("MT-2F 4A"))
        assertNull(MedtrumDeviceName.serialFromName("MT--1"))
    }

    @Test
    fun refusesAZeroSerial() {
        // Zero is how "no pump" is written everywhere else. It must never come back as a find.
        assertNull(MedtrumDeviceName.serialFromName("MT-0"))
        assertNull(MedtrumDeviceName.serialFromName("MT-00000000"))
        assertNull(MedtrumDeviceName.serialFromHex("0"))
    }

    @Test
    fun storedHexReadsBackAsTheSameNumber() {
        val serial = 0x76A5B3L
        val hex = MedtrumDeviceName.serialHex(serial)
        assertEquals("76A5B3", hex)
        assertEquals(serial, MedtrumDeviceName.serialFromHex(hex))
        // The name and the stored text are the same thing with and without the prefix.
        assertEquals("MT-76A5B3", MedtrumDeviceName.displayName(serial))
        assertEquals(serial, MedtrumDeviceName.serialFromName(MedtrumDeviceName.displayName(serial)))
    }

    @Test
    fun refusesStoredTextThatIsNotASerial() {
        assertNull(MedtrumDeviceName.serialFromHex(null))
        assertNull(MedtrumDeviceName.serialFromHex(""))
        // What the Android wizard writes when a pump answers with no serial in its advertisement.
        assertNull(MedtrumDeviceName.serialFromHex("MT"))
    }
}
