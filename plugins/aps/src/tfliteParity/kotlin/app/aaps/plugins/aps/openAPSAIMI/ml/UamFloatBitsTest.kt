package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The comparator must reject a vector whose bits are not the Android reference.
 * A wrong expected word is the failure we want to see before the real fixtures
 * are locked.
 */
class UamFloatBitsTest {

    @Test
    fun aDeliberatelyWrongBitDoesNotMatch() {
        val actual = floatBitsFromHex("3f800000")
        val wrongReference = floatBitsFromHex("00000000")
        assertEquals(false, sameFloatBits(actual, wrongReference))
        assertTrue(ulpDistance(actual, wrongReference) > 0)
    }

    @Test
    fun theAndroidReferenceHasSixtySevenVectors() {
        assertEquals(67, UamTfliteCorpus.rows.size)
        assertEquals(67, UamTfliteCorpus.VECTOR_COUNT)
        UamTfliteCorpus.rows.forEach { row ->
            assertEquals(UamTfliteCorpus.INPUTS + 1, row.size)
        }
        assertEquals("TFL3", UamTfliteCorpus.modelBytes.copyOfRange(4, 8).decodeToString())
        // Vector 0 is the all-zero input. Locked to the Android 2.4.0 word.
        assertEquals(floatBitsFromHex("3f9eea89"), UamTfliteCorpus.androidBits(0))
        assertEquals(floatBitsFromHex("4006ea45"), UamTfliteCorpus.androidBits(66))
    }
}
