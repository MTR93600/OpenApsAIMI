package app.aaps.plugins.aps.openAPSAIMI.math

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM-only oracle: [AimiTickPolicyMath.aimiMathRoundToLong] must match `java.lang.Math.round`
 * on every sample below. iOS runs the same function; the negative-half cases are also in commonTest.
 */
class AimiMathRoundJvmParityTest {

    @Test
    fun aimiMathRoundMatchesJavaMathRoundOnHalvesAndExtremes() {
        val samples = ArrayList<Double>(900)
        for (i in -400..400) samples.add(i / 2.0)
        for (i in -40..40) samples.add(i * 1.0e15)
        samples.add(Double.NaN)
        samples.add(Double.POSITIVE_INFINITY)
        samples.add(Double.NEGATIVE_INFINITY)
        samples.add(Long.MAX_VALUE.toDouble())
        samples.add(Long.MIN_VALUE.toDouble())
        samples.add(-0.0)
        for (value in samples) {
            assertEquals(
                java.lang.Math.round(value),
                AimiTickPolicyMath.aimiMathRoundToLong(value),
                "Math.round mismatch at $value",
            )
        }
    }

    @Test
    fun roundDigitsMatchesJavaMathRoundTimesScale() {
        val values = doubleArrayOf(-1.5, -1.25, -0.5, 0.0, 0.5, 1.25, 1.5, 2.5, 12.345, -12.345)
        for (value in values) {
            for (digits in 0..4) {
                val scale = 10.0.pow(digits.toDouble())
                val expected = if (!value.isFinite()) value else java.lang.Math.round(value * scale) / scale
                assertEquals(expected, AimiTickPolicyMath.round(value, digits), "value=$value digits=$digits")
            }
        }
    }
}
