package app.aaps.plugins.aps.openAPSAIMI.smb

import org.junit.Assert.assertEquals
import org.junit.Test

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
        // 0.03 -> 0.1 (safety floor with quantizeToPumpStep)
        assertEquals(0.1f, SmbQuantizer.quantizeToPumpStep(0.03f, 0.1f), 0.001f)
    }

    @Test
    fun `test quantizeToPumpStep safety floor`() {
        // 0.024 -> quantizes to 0.0 normally (0.024 < 0.025)
        // But safety floor logic: if quantized == 0 and units > 0.02 -> return step
        assertEquals(0.05f, SmbQuantizer.quantizeToPumpStep(0.024f, 0.05f), 0.001f)
        
        // 0.01 -> 0.0 (below 0.02 threshold)
        assertEquals(0.0f, SmbQuantizer.quantizeToPumpStep(0.01f, 0.05f), 0.001f)
    }
}
