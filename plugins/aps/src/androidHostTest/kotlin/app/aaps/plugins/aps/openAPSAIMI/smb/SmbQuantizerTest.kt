package app.aaps.plugins.aps.openAPSAIMI.smb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class SmbQuantizerTest {

    @Test
    fun `test quantize`() {
        // 1.03 -> 1.05 (step 0.05)
        assertEquals(1.05, SmbQuantizer.quantize(1.03, 0.05), 0.01)
        // 1.02 -> 1.00
        assertEquals(1.00, SmbQuantizer.quantize(1.02, 0.05), 0.01)
    }

    @Test
    fun `test quantize with 0_1 step`() {
        // 0.13 -> 0.1 (step 0.1)
        assertEquals(0.10, SmbQuantizer.quantize(0.13, 0.1), 0.01)
        // 0.16 -> 0.2
        assertEquals(0.20, SmbQuantizer.quantize(0.16, 0.1), 0.01)
        // 0.03 rounds DOWN to 0.0 — the old "boost to one step" floor was removed on purpose:
        // on large-step pumps (Combo 0.1U) it turned a 0.03U request into a 3x overdose.
        assertEquals(0.0f, SmbQuantizer.quantizeToPumpStep(0.03f, 0.1f), 0.001f)
    }

    @Test
    fun `test quantizeToPumpStep has no boost floor`() {
        // Standard nearest-step rounding only. The former safety floor ("if quantized == 0 and
        // units > 0.02 -> deliver one full step") was deliberately REMOVED because it overdosed
        // on pumps with large steps; these expectations lock in the safer current behaviour.
        assertEquals(0.0f, SmbQuantizer.quantizeToPumpStep(0.024f, 0.05f), 0.001f)
        assertEquals(0.0f, SmbQuantizer.quantizeToPumpStep(0.01f, 0.05f), 0.001f)
        // At/above half a step, normal rounding delivers the step.
        assertEquals(0.05f, SmbQuantizer.quantizeToPumpStep(0.026f, 0.05f), 0.001f)
    }
}
