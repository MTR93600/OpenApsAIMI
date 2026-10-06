package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares raw float32 bits with the Android 2.4.0 reference in [UamTfliteCorpus].
 *
 * This process still links TensorFlow Lite C 2.10.0. The locked gap below is
 * that runtime against the Android arm64-v8a words. It is not parity.
 * The equality gate is `build-uam24-sim-arm64.sh` (tag v2.4.0 compiled for
 * iossimulator-arm64). `run-uam24-x64.sh` records the published x86_64 slice
 * against the same arm64 words. A 2.4 runtime in this process must match.
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
 * no delegate, against Android Interpreter 2.4.0. 43 of 67 words differ against the arm64-v8a reference.
 * This is the rejected runtime, not an accepted tolerance.
 */
private const val TENSORFLOW_LITE_C_210_GAPS =
    "0 android=3f9eea89 ios=3f9eea87 ulp=2\n" +
        "1 android=3c21f4d4 ios=3c21f52c ulp=88\n" +
        "2 android=3f9db297 ios=3f9db295 ulp=2\n" +
        "3 android=3f9b510b ios=3f9b5108 ulp=3\n" +
        "5 android=3f9e9de3 ios=3f9e9de0 ulp=3\n" +
        "6 android=3fa34094 ios=3fa34093 ulp=1\n" +
        "7 android=3fa22b61 ios=3fa22b60 ulp=1\n" +
        "8 android=3f9f031c ios=3f9f031a ulp=2\n" +
        "9 android=3f9e7718 ios=3f9e7719 ulp=1\n" +
        "12 android=3f85926a ios=3f859268 ulp=2\n" +
        "13 android=3f81e22f ios=3f81e230 ulp=1\n" +
        "14 android=3f9eb8a9 ios=3f9eb8a8 ulp=1\n" +
        "15 android=3f9eb8cb ios=3f9eb8ca ulp=1\n" +
        "16 android=3f9edf53 ios=3f9edf52 ulp=1\n" +
        "18 android=3f9eeb81 ios=3f9eeb7e ulp=3\n" +
        "19 android=3f9eea0f ios=3f9eea0e ulp=1\n" +
        "24 android=3f886939 ios=3f88693a ulp=1\n" +
        "30 android=3e2689be ios=3e2689c0 ulp=2\n" +
        "31 android=3ea81f6e ios=3ea81f79 ulp=11\n" +
        "32 android=3e076eae ios=3e076e9e ulp=16\n" +
        "33 android=3fb5a81c ios=3fb5a81b ulp=1\n" +
        "34 android=3e0bb7fb ios=3e0bb7fd ulp=2\n" +
        "35 android=3e99d446 ios=3e99d444 ulp=2\n" +
        "36 android=40176cc4 ios=40176cc3 ulp=1\n" +
        "37 android=3eb5286e ios=3eb5286b ulp=3\n" +
        "40 android=404917dd ios=404917e0 ulp=3\n" +
        "41 android=3e80d1aa ios=3e80d1ac ulp=2\n" +
        "42 android=3edef372 ios=3edef374 ulp=2\n" +
        "43 android=3f1a1823 ios=3f1a1824 ulp=1\n" +
        "44 android=3e6afe51 ios=3e6afe5e ulp=13\n" +
        "45 android=40939a35 ios=40939a36 ulp=1\n" +
        "47 android=3f2f2378 ios=3f2f2372 ulp=6\n" +
        "48 android=3feef577 ios=3feef57b ulp=4\n" +
        "50 android=401040c9 ios=401040c8 ulp=1\n" +
        "53 android=3e354a3c ios=3e354a40 ulp=4\n" +
        "54 android=3f8195da ios=3f8195db ulp=1\n" +
        "56 android=3f20064a ios=3f20064c ulp=2\n" +
        "57 android=3e543f4c ios=3e543f47 ulp=5\n" +
        "58 android=40620d98 ios=40620d97 ulp=1\n" +
        "59 android=3db95142 ios=3db95155 ulp=19\n" +
        "60 android=3e512648 ios=3e512644 ulp=4\n" +
        "62 android=3f814675 ios=3f814674 ulp=1\n" +
        "63 android=3fcf1e01 ios=3fcf1dff ulp=2"
