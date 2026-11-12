package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The push payload, locked to the bytes Android's `GarminSerializer` writes for the same map.
 * The Android test [GarminCiqBytesParityTest] checks those bytes against the serializer itself.
 */
class GarminCiqBytesTest {

    @Test
    fun integerMatchesTheAndroidVector() {
        assertContentEquals(
            byteArrayOf(-38, 122, -38, 122, 0, 0, 0, 5, 1, 0, 0, 0, 3),
            GarminCiqBytes.serialize(3),
        )
    }

    @Test
    fun stringMatchesTheAndroidVector() {
        assertContentEquals(
            byteArrayOf(
                -85, -51, -85, -51, 0, 0, 0, 16, 0, 14, 72, 101, 108, 108, 111, 44, 32, 119, 111,
                114, 108, 100, 33, 0, -38, 122, -38, 122, 0, 0, 0, 5, 3, 0, 0, 0, 0,
            ),
            GarminCiqBytes.serialize("Hello, world!"),
        )
    }

    @Test
    fun glucoseMessageBytesMatchTheAndroidGolden() {
        assertContentEquals(GarminCiqVectors.glucoseBytes(), GarminCiqBytes.serialize(GarminCiqVectors.glucoseMessage))
    }

    @Test
    fun glucoseMessageJsonKeepsAndroidKeyOrderAndTypes() {
        assertEquals(GarminCiqVectors.GLUCOSE_JSON, GarminCiqJson.of(GarminCiqVectors.glucoseMessage))
    }

    @Test
    fun aQuoteInTheKeyIsEscaped() {
        assertEquals("""{"key":"a\"b"}""", GarminCiqJson.of(linkedMapOf("key" to "a\"b")))
    }
}
