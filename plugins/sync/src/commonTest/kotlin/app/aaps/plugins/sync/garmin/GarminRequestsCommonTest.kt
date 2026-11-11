package app.aaps.plugins.sync.garmin

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of [GarminRequests] whose text could differ on the iPhone: the number formats and the
 * query reading. The watch reads the exact text, so it must be the text Android sends.
 */
class GarminRequestsCommonTest {

    @Test
    fun oneDecimalIsRoundedHalfUpLikeJavaFormat() {
        assertEquals("0.8", GarminRequests.formatOneDecimal(0.8))
        assertEquals("1.0", GarminRequests.formatOneDecimal(1.0))
        assertEquals("0.0", GarminRequests.formatOneDecimal(0.0))
        assertEquals("0.1", GarminRequests.formatOneDecimal(0.05))
        assertEquals("0.2", GarminRequests.formatOneDecimal(0.15))
        assertEquals("0.3", GarminRequests.formatOneDecimal(0.25))
        assertEquals("0.4", GarminRequests.formatOneDecimal(0.35))
        assertEquals("1.4", GarminRequests.formatOneDecimal(1.44))
        assertEquals("12.0", GarminRequests.formatOneDecimal(11.96))
    }

    @Test
    fun numbersAreWrittenToTheJsonAsOnAndroid() {
        val json = buildJsonObject {
            put("a", 3.14)
            put("b", 90.0)
            put("c", 2.7 + 2.5)
            put("d", 0.8)
            put("e", 70)
            put("f", -90_000L)
            put("g", null as Double?)
        }.toString()

        assertEquals("""{"a":3.14,"b":90.0,"c":5.2,"d":0.8,"e":70,"f":-90000,"g":null}""", json)
    }

    @Test
    fun theQueryIsReadLikeTheAndroidPlugin() {
        val query = GarminQuery("key=abc&wait=10&steps5=12&empty=&a=b=c")

        assertEquals("abc", query["key"])
        assertEquals("10", query["wait"])
        assertEquals("", query["empty"])
        // A pair with two "=" is not a pair, as before.
        assertNull(query["a"])
        assertNull(query["missing"])
        assertTrue(query.contains("STEPS"))
        assertFalse(GarminQuery(null).contains("steps"))
        assertNull(GarminQuery(null)["key"])
    }
}
