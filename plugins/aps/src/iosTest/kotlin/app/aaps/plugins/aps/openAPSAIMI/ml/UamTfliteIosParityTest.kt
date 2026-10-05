package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bit-for-bit comparison against the Android 2.4.0 reference in [UamTfliteCorpus].
 * A mismatch lists every vector: index, Android bits, iOS bits, ULP distance.
 */
class UamTfliteIosParityTest {

    @Test
    fun theEmbeddedModelIsTheCommittedFile() {
        val bytes = UamTfliteCorpus.modelBytes
        assertEquals(4504, bytes.size)
        assertEquals("TFL3", bytes.copyOfRange(4, 8).decodeToString())
    }

    @Test
    fun eachVectorMatchesTheAndroidInterpreterBits() {
        val version = IosUamTflite.version()
        assertTrue(version.startsWith("2.10"), "TensorFlow Lite C version was `$version`")
        val model = UamTfliteCorpus.modelBytes
        val mismatches = ArrayList<String>()
        for (index in 0 until UamTfliteCorpus.VECTOR_COUNT) {
            val actual = IosUamTflite.infer(model, UamTfliteCorpus.inputFloats(index)).toRawBits()
            val expected = UamTfliteCorpus.androidBits(index)
            if (!sameFloatBits(actual, expected)) {
                mismatches += "$index android=${floatBitsHex(expected)} ios=${floatBitsHex(actual)} ulp=${ulpDistance(actual, expected)}"
            }
        }
        assertEquals(0, mismatches.size, mismatches.joinToString("\n"))
    }
}
