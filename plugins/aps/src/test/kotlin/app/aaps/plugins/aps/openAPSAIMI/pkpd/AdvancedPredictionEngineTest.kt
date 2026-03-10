package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedPredictionEngineTest {

    @Test
    fun `test predict no horizon`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        val result = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 0
        )
        assertEquals(1, result.size)
        assertEquals(100.0, result[0], 0.0)
    }

    @Test
    fun `test predict flat with no IOB or COB`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val result = AdvancedPredictionEngine.predict(
            currentBG = 100.0,
            iobArray = emptyArray(),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 60
        )
        // Should remain flat at 100
        assertTrue(result.all { it == 100.0 })
    }

    @Test
    fun `test predict drop with IOB`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.carb_ratio } returns 10.0
        every { profile.peakTime } returns 75.0

        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 1.0
        every { iobEntry.time } returns System.currentTimeMillis()

        val result = AdvancedPredictionEngine.predict(
            currentBG = 200.0,
            iobArray = arrayOf(iobEntry),
            finalSensitivity = 50.0,
            cobG = 0.0,
            profile = profile,
            horizonMinutes = 60
        )
        // Should drop
        assertTrue(result.last() < 200.0)
    }
}
