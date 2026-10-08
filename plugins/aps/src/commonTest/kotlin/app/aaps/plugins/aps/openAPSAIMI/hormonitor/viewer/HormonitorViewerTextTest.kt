package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDateFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The portable text logic against a scripted [AimiDateFormatter]: the fallback paths
 * and the null handling, independent of any platform date API.
 */
class HormonitorViewerTextTest {

    private val fake = object : AimiDateFormatter {
        override fun format(mills: Long, pattern: String): String = "$pattern@$mills"
        override fun parse(text: String, pattern: String): Long? =
            if (text == "2026-10-08" && pattern == "yyyy-MM-dd") 1727827200000L else null
    }

    @Test
    fun shortDayValidParsesThenFormats() {
        assertEquals("EEE d MMM@1727827200000", shortDayText("2026-10-08", fake))
    }

    @Test
    fun shortDayUnparseableReturnsInputUnchanged() {
        assertEquals("not-a-date", shortDayText("not-a-date", fake))
    }

    @Test
    fun shortDayEmptyReturnsInputUnchanged() {
        assertEquals("", shortDayText("", fake))
    }

    @Test
    fun timeSpanBothNullReturnsDash() {
        assertEquals("—", timeSpanText(null, null, fake))
    }

    @Test
    fun timeSpanFirstNullReturnsDash() {
        assertEquals("—", timeSpanText(null, 2000L, fake))
    }

    @Test
    fun timeSpanLastNullReturnsDash() {
        assertEquals("—", timeSpanText(1000L, null, fake))
    }

    @Test
    fun timeSpanFormatsBothEnds() {
        assertEquals("HH:mm@1000 – HH:mm@2000", timeSpanText(1000L, 2000L, fake))
    }
}
