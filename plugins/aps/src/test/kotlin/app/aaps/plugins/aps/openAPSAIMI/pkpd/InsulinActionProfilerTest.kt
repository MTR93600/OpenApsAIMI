package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class InsulinActionProfilerTest {

    @Test
    fun `test calculate empty`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.peakTime } returns 75
        
        val result = InsulinActionProfiler.calculate(emptyArray(), profile)
        assertEquals(0.0, result.iobTotal, 0.0)
    }

    @Test
    fun `test calculate single bolus`() {
        val profile = mockk<OapsProfileAimi>(relaxed = true)
        every { profile.peakTime } returns 75
        
        val iobEntry = mockk<IobTotal>()
        every { iobEntry.iob } returns 1.0
        every { iobEntry.time } returns System.currentTimeMillis()
        
        val result = InsulinActionProfiler.calculate(arrayOf(iobEntry), profile)
        assertEquals(1.0, result.iobTotal, 0.0)
        // Peak minutes should be close to 75 (since bolus was just now)
        assertEquals(75.0, result.peakMinutes, 1.0)
    }
}
