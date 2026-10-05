package app.aaps.plugins.aps.openAPSAIMI

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Civil fields match `java.time` / `Calendar` for a fixed instant.
 * 2026-10-04 is a Sunday. `Calendar` numbers Sunday as 1.
 * 12:34:56 UTC is 14:34:56 in Europe/Prague (CEST, UTC+2).
 */
class AimiCivilClockTest {

    private val noonUtc = 1_791_117_296_000L

    @Test
    fun pinnedLocalTimeKeepsTheHourOnAFixedDay() {
        val zone = TimeZone.UTC
        assertEquals(14, aimiCivilClock(aimiEpochAtLocalTime(14, zone = zone), zone).hour)
        assertEquals(0, aimiCivilClock(aimiEpochAtLocalTime(14, zone = zone), zone).minute)
        assertEquals(5, aimiCivilClock(aimiEpochAtLocalTime(5, zone = zone), zone).hour)
    }

    @Test
    fun utcSundayNoonKeepsHourMinuteSecondAndCalendarDay() {
        val clock = aimiCivilClock(noonUtc, TimeZone.UTC)
        assertEquals(12, clock.hour)
        assertEquals(34, clock.minute)
        assertEquals(56, clock.second)
        assertEquals(1, clock.calendarDayOfWeek)
    }

    @Test
    fun pragueIsTwoHoursAheadOnThatSunday() {
        val clock = aimiCivilClock(noonUtc, TimeZone.of("Europe/Prague"))
        assertEquals(14, clock.hour)
        assertEquals(34, clock.minute)
        assertEquals(56, clock.second)
        assertEquals(1, clock.calendarDayOfWeek)
    }

    @Test
    fun csvStampsUseLocaleUsGregorianDigits() {
        assertEquals("2026-10-04 12:34:56", aimiCsvTimestamp(noonUtc, TimeZone.UTC))
        assertEquals("2026-10-04 12:34", aimiCsvTimestampMinute(noonUtc, TimeZone.UTC))
    }

    @Test
    fun badDayWindowIsStrictlyBetween0005And0010() {
        val zone = TimeZone.UTC
        assertFalse(aimiStrictlyInsideLocalWindow(1_791_072_300_000L, 0, 5, 0, 10, zone))
        assertTrue(aimiStrictlyInsideLocalWindow(1_791_072_301_000L, 0, 5, 0, 10, zone))
        assertTrue(aimiStrictlyInsideLocalWindow(1_791_072_599_000L, 0, 5, 0, 10, zone))
        assertFalse(aimiStrictlyInsideLocalWindow(1_791_072_600_000L, 0, 5, 0, 10, zone))
    }

    @Test
    fun yesterdayMiddayIsNoonOnThePreviousLocalDate() {
        assertEquals(1_791_028_800_000L, aimiYesterdayMiddayEpochMs(noonUtc, TimeZone.UTC))
    }
}
