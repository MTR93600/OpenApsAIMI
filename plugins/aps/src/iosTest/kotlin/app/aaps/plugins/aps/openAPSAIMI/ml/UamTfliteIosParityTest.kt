package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares raw float32 bits with the Android 2.4.0 arm64-v8a reference in
 * [UamTfliteCorpus]. The linked runtime must be TensorFlow Lite C 2.4, one
 * thread, no delegate. Any differing word fails the test.
 *
 * Nothing in the basal tick calls this. `predictSmbUam` stays on the
 * model-absent path.
 */
class UamTfliteIosParityTest {

    @Test
    fun theEmbeddedModelIsTheCommittedFile() {
        val bytes = UamTfliteCorpus.modelBytes
        assertEquals(4504, bytes.size)
        assertEquals("TFL3", bytes.copyOfRange(4, 8).decodeToString())
    }

    @Test
    fun linkedRuntimeAgainstTheAndroidInterpreterBits() {
        val version = IosUamTflite.version()
        val model = UamTfliteCorpus.modelBytes
        val mismatches = ArrayList<String>()
        for (index in 0 until UamTfliteCorpus.VECTOR_COUNT) {
            val actual = IosUamTflite.infer(model, UamTfliteCorpus.inputFloats(index)).toRawBits()
            val expected = UamTfliteCorpus.androidBits(index)
            if (!sameFloatBits(actual, expected)) {
                mismatches += "$index android=${floatBitsHex(expected)} ios=${floatBitsHex(actual)} ulp=${ulpDistance(actual, expected)}"
            }
        }
        val report = mismatches.joinToString("\n")
        assertEquals(0, mismatches.size, "version=$version\n$report")
        assertEquals(UamTfliteCorpus.VECTOR_COUNT, 67)
        assertTrue(version.startsWith("2.4"), "TensorFlow Lite C version was `$version`")
    }
}
