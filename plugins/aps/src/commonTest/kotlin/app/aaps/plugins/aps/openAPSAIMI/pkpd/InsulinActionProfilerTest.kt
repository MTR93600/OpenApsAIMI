package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import kotlin.test.Test
import kotlin.test.assertEquals

class InsulinActionProfilerTest {

    @Test
    fun `test calculate empty`() {
        val profile = zeroedAimiProfile(peakTime = 75.0)

        val result = InsulinActionProfiler.calculate(emptyArray(), profile)
        assertEquals(0.0, result.iobTotal, 0.0)
    }

    @Test
    fun `test calculate single bolus`() {
        val profile = zeroedAimiProfile(peakTime = 75.0)

        val iobEntry = IobTotal(time = aimiWallClockMs(), iob = 1.0)

        val result = InsulinActionProfiler.calculate(arrayOf(iobEntry), profile)
        assertEquals(1.0, result.iobTotal, 0.0)
        // Peak minutes should be close to 75 (since bolus was just now)
        assertEquals(75.0, result.peakMinutes, 1.0)
    }
}
