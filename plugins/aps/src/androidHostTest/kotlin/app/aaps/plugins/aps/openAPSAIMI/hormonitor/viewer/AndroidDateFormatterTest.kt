package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the platform contract of [AndroidDateFormatter]: an unreadable input parses to
 * `null` (never throws), so [shortDayText] returns the input unchanged — the same
 * observable behavior as the original `runCatching { ... }.getOrDefault(dayLocal)`.
 */
class AndroidDateFormatterTest {

    private val fmt = AndroidDateFormatter()

    @Test
    fun parseGarbageReturnsNull() {
        assertNull(fmt.parse("not-a-date", "yyyy-MM-dd"))
    }

    @Test
    fun parseEmptyReturnsNull() {
        assertNull(fmt.parse("", "yyyy-MM-dd"))
    }

    @Test
    fun parseValidDateReturnsMillis() {
        val mills = fmt.parse("2026-10-08", "yyyy-MM-dd")
        assertTrue(mills != null && mills > 0)
    }

    @Test
    fun formatProducesNonEmptyText() {
        assertTrue(fmt.format(1_700_000_000_000L, "EEE d MMM").isNotEmpty())
    }
}
