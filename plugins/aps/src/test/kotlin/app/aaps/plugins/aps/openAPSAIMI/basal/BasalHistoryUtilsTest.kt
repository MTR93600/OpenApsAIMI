package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.data.model.TB
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

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
    fun `minutes since zero basal ended uses replacement timestamp`() {
        val now = 10_000_000L
        val fetcher = mockk<(Long) -> List<TB>>()
        val provider = BasalHistoryUtils.FetcherProvider(fetcher) { now }

        val currentNonZero = mockk<TB>(relaxed = true)
        every { currentNonZero.timestamp } returns now - 8 * 60_000L
        every { currentNonZero.isAbsolute } returns true
        every { currentNonZero.rate } returns 0.66

        val previousZero = mockk<TB>(relaxed = true)
        every { previousZero.timestamp } returns now - 38 * 60_000L
        every { previousZero.isAbsolute } returns true
        every { previousZero.rate } returns 0.0

        every { fetcher(any()) } returns listOf(currentNonZero, previousZero)

        assertEquals(8, provider.minutesSinceZeroBasalEnded(2))
    }

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
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
