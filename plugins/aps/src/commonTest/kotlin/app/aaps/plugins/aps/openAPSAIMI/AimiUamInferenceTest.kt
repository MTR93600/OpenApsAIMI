package app.aaps.plugins.aps.openAPSAIMI

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The steps around the UAM model call. Shared by Android and iOS, and run here on the JVM and on the
 * iOS simulator, so a raw model output gives the same SMB on both phones.
 */
class AimiUamInferenceTest {

    @Test
    fun `a raw output is cut to four decimals toward zero`() {
        assertEquals(0.1234f, AimiUamInference.doseFromRaw(0.12349f))
        assertEquals(1.0f, AimiUamInference.doseFromRaw(1.00009f))
    }

    @Test
    fun `a negative output gives no SMB`() {
        assertEquals(0f, AimiUamInference.doseFromRaw(-0.5f))
    }

    @Test
    fun `an output that is not a number gives no SMB`() {
        assertEquals(0f, AimiUamInference.doseFromRaw(Float.NaN))
        assertEquals(0f, AimiUamInference.doseFromRaw(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `inputs that are not finite become 0 and are counted`() {
        val (out, replaced) = AimiUamInference.sanitizeWithCount(floatArrayOf(1f, Float.NaN, Float.NEGATIVE_INFINITY, 2f))

        assertEquals(listOf(1f, 0f, 0f, 2f), out.toList())
        assertEquals(2, replaced)
    }
}
