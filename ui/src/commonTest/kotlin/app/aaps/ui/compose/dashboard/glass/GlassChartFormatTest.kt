package app.aaps.ui.compose.dashboard.glass

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks that the Glass chart text matches the `String.format(Locale.US, ...)` output of `dev_OAPSAIMI`. */
class GlassChartFormatTest {

    // 2026-03-05 07:04:00 UTC
    private val epochMs = 1_772_694_240_000L
    private val utc = TimeZone.UTC

    @Test
    fun `hour label is two digits`() {
        assertEquals("07", glassHourLabel(epochMs, 0L, utc))
        assertEquals("01", glassHourLabel(epochMs, 6 * 60L, utc))
        assertEquals("23", glassHourLabel(epochMs, 8 * 60L, utc))
    }

    @Test
    fun `clock label is padded hours and minutes`() {
        assertEquals("07:04", glassClockLabel(epochMs, utc))
        assertEquals("06:59", glassClockLabel(epochMs, 5L, utc))
    }

    @Test
    fun `clock label follows the time zone`() {
        assertEquals("08:04", glassClockLabel(epochMs, TimeZone.of("Europe/Paris")))
    }

    @Test
    fun `date label is day month year`() {
        assertEquals("05/03/2026", glassDateLabel(epochMs, utc))
    }

    @Test
    fun `fixed decimals use a dot and round half up`() {
        assertEquals("2.0", glassFixedDecimals(2f, 1))
        assertEquals("0.25", glassFixedDecimals(0.25f, 2))
        assertEquals("1.50", glassFixedDecimals(1.5f, 2))
        assertEquals("0.3", glassFixedDecimals(0.25f, 1))
    }
}
