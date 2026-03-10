package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.data.model.TB
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BasalHistoryUtilsTest {

    @Test
    fun `test FetcherProvider zeroBasalDurationMinutes`() {
        val now = 1000000L
        val fetcher = mockk<(Long) -> List<TB>>()
        val provider = BasalHistoryUtils.FetcherProvider(fetcher) { now }

        // Mock events: 
        // 1. Zero basal from now-10m to now (duration 10m)
        // 2. Non-zero before that
        val tbZero = mockk<TB>(relaxed = true)
        every { tbZero.timestamp } returns now - 10 * 60000
        every { tbZero.duration } returns 10
        every { tbZero.isAbsolute } returns true
        every { tbZero.rate } returns 0.0

        val tbNormal = mockk<TB>(relaxed = true)
        every { tbNormal.timestamp } returns now - 20 * 60000
        every { tbNormal.duration } returns 10
        every { tbNormal.isAbsolute } returns true
        every { tbNormal.rate } returns 1.0

        every { fetcher(any()) } returns listOf(tbZero, tbNormal)

        val duration = provider.zeroBasalDurationMinutes(1)
        assertEquals(10, duration)
    }

    @Test
    fun `test FetcherProvider lastTempIsZero`() {
        val now = 1000000L
        val fetcher = mockk<(Long) -> List<TB>>()
        val provider = BasalHistoryUtils.FetcherProvider(fetcher) { now }

        // Case 1: Active zero temp
        val tbZero = mockk<TB>(relaxed = true)
        every { tbZero.timestamp } returns now - 5 * 60000
        every { tbZero.duration } returns 30 // Active
        every { tbZero.isAbsolute } returns true
        every { tbZero.rate } returns 0.0

        every { fetcher(any()) } returns listOf(tbZero)
        assertTrue(provider.lastTempIsZero())

        // Case 2: Active non-zero temp
        val tbNormal = mockk<TB>(relaxed = true)
        every { tbNormal.timestamp } returns now - 5 * 60000
        every { tbNormal.duration } returns 30
        every { tbNormal.isAbsolute } returns true
        every { tbNormal.rate } returns 1.0

        every { fetcher(any()) } returns listOf(tbNormal)
        assertFalse(provider.lastTempIsZero())
    }
}
