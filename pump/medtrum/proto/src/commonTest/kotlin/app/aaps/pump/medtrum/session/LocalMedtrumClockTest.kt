package app.aaps.pump.medtrum.session

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalMedtrumClockTest {

    private fun clock(offsetMinutes: Int, now: Long = 0L) = LocalMedtrumClock(now = { now }, offsetMinutesAt = { offsetMinutes })

    @Test
    fun minuteOfDayIsLocalTimeNotUtc() {
        // 10:00 UTC, phone at UTC+2 -> 12:00 local
        val tenUtc = 10 * 60 * 60_000L
        assertEquals(12 * 60, clock(120).minuteOfDay(tenUtc))
        assertEquals(10 * 60, clock(0).minuteOfDay(tenUtc))
    }

    @Test
    fun minuteOfDayWrapsAroundMidnight() {
        val twentyThreeUtc = 23 * 60 * 60_000L
        assertEquals(60, clock(120).minuteOfDay(twentyThreeUtc)) // 01:00 next day
        val oneUtc = 60 * 60_000L
        assertEquals(23 * 60, clock(-120).minuteOfDay(oneUtc)) // 23:00 previous day
    }

    @Test
    fun minuteOfDayWorksBeforeTheEpoch() {
        assertEquals(1439, clock(0).minuteOfDay(-1L))
        assertEquals(1439, clock(0).minuteOfDay(-60_000L))
        assertEquals(1438, clock(0).minuteOfDay(-60_001L))
    }

    @Test
    fun nowAndOffsetComeFromTheSources() {
        val c = clock(offsetMinutes = 330, now = 1234L)
        assertEquals(1234L, c.nowMillis())
        assertEquals(330, c.utcOffsetMinutes(0L))
    }
}
