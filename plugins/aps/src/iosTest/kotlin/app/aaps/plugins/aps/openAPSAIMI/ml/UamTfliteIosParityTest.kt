package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares raw float32 bits with the Android 2.4.0 reference in [UamTfliteCorpus].
 *
 * TensorFlow Lite C 2.4.0 has no arm64 simulator slice, so this process links
 * 2.10.0 and does not match. That gap is locked here and is not a pass of
 * parity. The equality gate is `run-uam24-x64.sh`, which executes the 2.4.0
 * x86_64 slice. A 2.4 runtime in this process would be required to match.
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
        if (version.startsWith("2.4")) {
            assertEquals(0, mismatches.size, report)
        } else {
            assertTrue(version.startsWith("2.10"), "TensorFlow Lite C version was `$version`")
            assertEquals(TENSORFLOW_LITE_C_210_GAPS, report)
        }
    }
}

/**
 * Measured on the arm64 simulator with TensorFlow Lite C 2.10.0, one thread,
 * no delegate, against Android Interpreter 2.4.0. 43 of 67 words differ.
 * This is the rejected runtime, not an accepted tolerance.
 */
private const val TENSORFLOW_LITE_C_210_GAPS =
    "0 android=3f9eea89 ios=3f9eea87 ulp=2\n" +
        "1 android=3c21f49c ios=3c21f52c ulp=144\n" +
        "2 android=3f9db298 ios=3f9db295 ulp=3\n" +
        "3 android=3f9b5109 ios=3f9b5108 ulp=1\n" +
        "4 android=3fa0008b ios=3fa0008c ulp=1\n" +
        "5 android=3f9e9de3 ios=3f9e9de0 ulp=3\n" +
        "6 android=3fa34094 ios=3fa34093 ulp=1\n" +
        "9 android=3f9e771a ios=3f9e7719 ulp=1\n" +
        "13 android=3f81e231 ios=3f81e230 ulp=1\n" +
        "15 android=3f9eb8cb ios=3f9eb8ca ulp=1\n" +
        "16 android=3f9edf51 ios=3f9edf52 ulp=1\n" +
        "17 android=3f9ee77a ios=3f9ee779 ulp=1\n" +
        "18 android=3f9eeb80 ios=3f9eeb7e ulp=2\n" +
        "19 android=3f9eea0f ios=3f9eea0e ulp=1\n" +
        "22 android=3fde324f ios=3fde3250 ulp=1\n" +
        "23 android=3e85546f ios=3e85546d ulp=2\n" +
        "28 android=3f14ecf2 ios=3f14ecef ulp=3\n" +
        "29 android=3f76e3a4 ios=3f76e39d ulp=7\n" +
        "30 android=3e2689c2 ios=3e2689c0 ulp=2\n" +
        "31 android=3ea81f76 ios=3ea81f79 ulp=3\n" +
        "32 android=3e076ea9 ios=3e076e9e ulp=11\n" +
        "33 android=3fb5a81c ios=3fb5a81b ulp=1\n" +
        "34 android=3e0bb7fb ios=3e0bb7fd ulp=2\n" +
        "35 android=3e99d44b ios=3e99d444 ulp=7\n" +
        "36 android=40176cc5 ios=40176cc3 ulp=2\n" +
        "37 android=3eb52873 ios=3eb5286b ulp=8\n" +
        "40 android=404917e4 ios=404917e0 ulp=4\n" +
        "41 android=3e80d1b0 ios=3e80d1ac ulp=4\n" +
        "42 android=3edef370 ios=3edef374 ulp=4\n" +
        "46 android=3eb149d7 ios=3eb149d3 ulp=4\n" +
        "48 android=3feef579 ios=3feef57b ulp=2\n" +
        "49 android=3f02b2bb ios=3f02b2bc ulp=1\n" +
        "51 android=405a72ae ios=405a72ad ulp=1\n" +
        "52 android=3e1fd905 ios=3e1fd90c ulp=7\n" +
        "53 android=3e354a37 ios=3e354a40 ulp=9\n" +
        "54 android=3f8195d7 ios=3f8195db ulp=4\n" +
        "55 android=3fb3c3e3 ios=3fb3c3e5 ulp=2\n" +
        "56 android=3f20064b ios=3f20064c ulp=1\n" +
        "57 android=3e543f4f ios=3e543f47 ulp=8\n" +
        "59 android=3db9514a ios=3db95155 ulp=11\n" +
        "63 android=3fcf1e02 ios=3fcf1dff ulp=3\n" +
        "64 android=3fada122 ios=3fada121 ulp=1\n" +
        "66 android=4006ea45 ios=4006ea44 ulp=1"
